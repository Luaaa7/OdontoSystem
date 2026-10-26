package com.odontosystem.OdontoSystem.citas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odontosystem.OdontoSystem.citas.CitaDtos.CitaResponse;
import com.odontosystem.OdontoSystem.citas.PasarelaMercadoPago.MercadoPagoNoDisponible;
import com.odontosystem.OdontoSystem.citas.PasarelaMercadoPago.Orden;
import com.odontosystem.OdontoSystem.citas.PasarelaSimulada.Cobro;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoIntentoPago;
import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.EstadoWebhookPago;
import com.odontosystem.OdontoSystem.entity.EventoPagoWebhook;
import com.odontosystem.OdontoSystem.entity.IntentoPago;
import com.odontosystem.OdontoSystem.entity.MetodoPago;
import com.odontosystem.OdontoSystem.entity.Pago;
import com.odontosystem.OdontoSystem.entity.PasarelaPago;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.EventoPagoWebhookRepository;
import com.odontosystem.OdontoSystem.repository.IntentoPagoRepository;
import com.odontosystem.OdontoSystem.repository.PagoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Pago del depósito de una cita (RF04 / RF13). Flujo:
 * <ol>
 *   <li>Se crea el pago PENDIENTE y un intento de cobro con clave de idempotencia.</li>
 *   <li>La pasarela cobra y confirma.</li>
 *   <li>La confirmación se verifica y se registra como aviso PROCESADO.</li>
 *   <li>Recién entonces el pago pasa a VERIFICADO (la BD lo exige) y la cita a CONFIRMADA,
 *       o a PENDIENTE_CONFIRMACION si el odontólogo aprueba sus citas a mano (RF11).</li>
 * </ol>
 * Dos modos (PAGOS_MODO):
 * <ul>
 *   <li>{@code simulado}: {@link PasarelaSimulada} aprueba todo y firma su aviso con HMAC.</li>
 *   <li>{@code mercadopago}: API de Orders de Mercado Pago. El frontend tokeniza con MercadoPago.js / Bricks y manda
 *       el token y el medio ({@code visa}, {@code master}, {@code debvisa}, {@code yape}…); el backend crea la orden con
 *       el Access Token. La respuesta de la API (HTTPS + nuestro token) es la confirmación verificada. Si Mercado Pago
 *       no responde, el pago queda PENDIENTE y lo cierra el aviso (webhook), que se verifica consultando la orden.</li>
 * </ul>
 * Plin todavía no lo cobra ninguna pasarela: se rechaza en cualquier modo ("próximamente").
 */
@Service
public class PagoService {

    private static final Logger log = LoggerFactory.getLogger(PagoService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PLIN_PROXIMAMENTE = "Plin estará disponible próximamente; por ahora paga con tarjeta o Yape";

    /**
     * token: el que entrega Mercado Pago al tokenizar (MercadoPago.js o Bricks). medioPagoId: solo Mercado Pago, el medio que devuelve el Brick ({@code visa},
     * {@code master}, {@code debvisa}, {@code yape}…). En modo simulado no hace falta ninguno.
     */
    public record PagoRequest(@jakarta.validation.constraints.NotNull MetodoPago metodo,
                              @jakarta.validation.constraints.Size(max = 255) String token,
                              @jakarta.validation.constraints.Size(max = 40) String medioPagoId) {
        public PagoRequest(MetodoPago metodo) {
            this(metodo, null, null);
        }

        public PagoRequest(MetodoPago metodo, String token) {
            this(metodo, token, null);
        }
    }

    /** estado: VERIFICADO (pagado), RECHAZADO (puede reintentar con otro medio) o PENDIENTE (se confirma en minutos). */
    public record PagoResponse(UUID pagoId, EstadoPago estado, MetodoPago metodo, BigDecimal monto,
                               String numeroOperacion, OffsetDateTime verificadoEn, boolean simulado,
                               CitaResponse cita, String mensaje) {}

    /** Lo que se hizo con un aviso de la pasarela (para el log y la respuesta del webhook). */
    public enum ResultadoAviso { CONFIRMADO, CONFIRMADO_Y_DEVUELTO, RECHAZADO, YA_PROCESADO, IGNORADO }

    private final CitaRepository citaRepository;
    private final PagoRepository pagoRepository;
    private final IntentoPagoRepository intentoRepository;
    private final EventoPagoWebhookRepository eventoRepository;
    private final CitaService citaService;
    private final PasarelaSimulada pasarela;
    private final PasarelaMercadoPago mercadoPago;
    private final ReembolsoService reembolsos;
    private final Clock clock;
    private final String modo;

    /** Sin Mercado Pago (solo el modo simulado). */
    public PagoService(CitaRepository citaRepository, PagoRepository pagoRepository,
                       IntentoPagoRepository intentoRepository, EventoPagoWebhookRepository eventoRepository,
                       CitaService citaService, PasarelaSimulada pasarela,
                       ReembolsoService reembolsos, Clock clock, String modo) {
        this(citaRepository, pagoRepository, intentoRepository, eventoRepository, citaService, pasarela,
                PasarelaMercadoPago.sinConfigurar(), reembolsos, clock, modo);
    }

    @Autowired
    public PagoService(CitaRepository citaRepository, PagoRepository pagoRepository,
                       IntentoPagoRepository intentoRepository, EventoPagoWebhookRepository eventoRepository,
                       CitaService citaService, PasarelaSimulada pasarela,
                       PasarelaMercadoPago mercadoPago, ReembolsoService reembolsos, Clock clock,
                       @Value("${app.pagos.modo:simulado}") String modo) {
        this.citaRepository = citaRepository;
        this.pagoRepository = pagoRepository;
        this.intentoRepository = intentoRepository;
        this.eventoRepository = eventoRepository;
        this.citaService = citaService;
        this.pasarela = pasarela;
        this.mercadoPago = mercadoPago;
        this.reembolsos = reembolsos;
        this.clock = clock;
        this.modo = modo;
    }

    public String modo() {
        return modo;
    }

    /** Modo simulado (sin token). */
    @Transactional
    public PagoResponse pagarDeposito(UUID pacienteId, UUID citaId, MetodoPago metodo) {
        return pagarDeposito(pacienteId, citaId, metodo, null);
    }

    @Transactional
    public PagoResponse pagarDeposito(UUID pacienteId, UUID citaId, MetodoPago metodo, String token) {
        return pagarDeposito(pacienteId, citaId, metodo, token, null);
    }

    @Transactional
    public PagoResponse pagarDeposito(UUID pacienteId, UUID citaId, MetodoPago metodo, String token, String medioPagoId) {
        boolean conMercadoPago = "mercadopago".equalsIgnoreCase(modo);
        if (!conMercadoPago && !"simulado".equalsIgnoreCase(modo)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Los pagos en línea aún no están habilitados");
        }
        if (metodo == MetodoPago.PLIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, PLIN_PROXIMAMENTE);
        }
        if (conMercadoPago) {
            validarParaMercadoPago(metodo, token, medioPagoId);
        }
        Cita cita = citaPendienteDePago(pacienteId, citaId);
        OffsetDateTime ahora = OffsetDateTime.now(clock);

        PasarelaPago procesador = conMercadoPago ? PasarelaPago.MERCADO_PAGO : PasarelaPago.SIMULADA;
        BigDecimal monto = cita.getMontoDepositoRequerido();
        Pago pago = pagoRepository.saveAndFlush(Pago.builder()
                .cita(cita).metodoPago(metodo).pasarelaPago(procesador)
                .monto(monto).estado(EstadoPago.PENDIENTE).build());
        IntentoPago intento = intentoRepository.saveAndFlush(IntentoPago.builder()
                .pago(pago).pasarelaPago(procesador)
                .claveIdempotencia(citaId + ":" + UUID.randomUUID())
                .monto(monto).estado(EstadoIntentoPago.PENDIENTE).build());

        if (conMercadoPago) {
            return cobrarConMercadoPago(cita, pago, intento, metodo, token, medioPagoId, ahora);
        }

        Cobro cobro = pasarela.cobrar(intento.getId(), monto, metodo.name());
        intento.setIdExterno(cobro.idExterno());
        intento.setEstado(EstadoIntentoPago.APROBADO);
        intento.setConfirmadoEn(ahora);
        intentoRepository.saveAndFlush(intento);

        registrarAvisoSimulado(pago, intento, cobro);
        CitaResponse citaActualizada = marcarPagado(pago, cobro.idExterno(), ahora);
        return new PagoResponse(pago.getId(), pago.getEstado(), metodo, monto, pago.getNumeroOperacion(),
                pago.getVerificadoEn(), true, citaActualizada, "Pago aprobado (simulado)");
    }

    // ==================================================================
    // Mercado Pago (API de Orders)
    // ==================================================================

    private void validarParaMercadoPago(MetodoPago metodo, String token, String medioPagoId) {
        if (!mercadoPago.configurada()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Los pagos en línea no están disponibles en este momento");
        }
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el token de Mercado Pago: abre el formulario de pago y vuelve a intentarlo");
        }
        String medio = medioPagoId == null ? "" : medioPagoId.strip().toLowerCase();
        if (metodo == MetodoPago.YAPE && !medio.isEmpty() && !medio.equals("yape")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El medio de pago no corresponde a Yape");
        }
        if (metodo == MetodoPago.TARJETA && (medio.isEmpty() || medio.equals("yape"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el medio de la tarjeta (medio_pago_id: visa, master, amex, debvisa…)");
        }
        if (!medio.matches("[a-z0-9_]{0,40}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Medio de pago inválido");
        }
    }

    private PagoResponse cobrarConMercadoPago(Cita cita, Pago pago, IntentoPago intento, MetodoPago metodo, String token,
                                              String medioPagoId, OffsetDateTime ahora) {
        long centimos = centimos(pago.getMonto());
        String medio = metodo == MetodoPago.YAPE ? "yape" : medioPagoId.strip().toLowerCase();
        Orden orden;
        try {
            orden = mercadoPago.cobrar(token.strip(), medio, centimos, cita.getPaciente().getCorreo(),
                    intento.getId().toString(), intento.getId().toString());
        } catch (MercadoPagoNoDisponible e) {
            // No sabemos si cobró: el pago queda PENDIENTE y lo cierra el aviso de Mercado Pago.
            log.warn("Mercado Pago no respondió al cobrar el pago {}: {}", pago.getId(), e.getMessage());
            intento.setMensajeError("Mercado Pago no respondió: " + e.getMessage());
            intentoRepository.saveAndFlush(intento);
            return respuesta(pago, metodo, null,
                    "Estamos confirmando tu pago con el banco. Te avisaremos en unos minutos; no vuelvas a pagar.");
        }

        if (orden.rechazada()) {
            intento.setIdExterno(orden.id());
            intento.setEstado(EstadoIntentoPago.RECHAZADO);
            intento.setMensajeError(orden.estado() + ": " + orden.detalle());
            intentoRepository.saveAndFlush(intento);
            pago.setEstado(EstadoPago.RECHAZADO);
            pagoRepository.saveAndFlush(pago);
            return respuesta(pago, metodo, null, orden.mensajeUsuario());
        }
        if (!orden.aprobada()) {
            // En curso (processing / action_required): lo cierra el aviso.
            intento.setIdExterno(orden.id());
            intento.setMensajeError("Orden " + orden.estado() + (orden.detalle() == null ? "" : " / " + orden.detalle()));
            intentoRepository.saveAndFlush(intento);
            return respuesta(pago, metodo, null, "Estamos confirmando tu pago. Te avisaremos en unos minutos; no vuelvas a pagar.");
        }
        if (orden.montoPagadoCentimos() != centimos) {
            log.error("Mercado Pago cobró {} céntimos para el pago {} (esperado {})",
                    orden.montoPagadoCentimos(), pago.getId(), centimos);
            intento.setIdExterno(orden.id());
            intento.setMensajeError("Monto distinto: " + orden.montoPagadoCentimos());
            intentoRepository.saveAndFlush(intento);
            return respuesta(pago, metodo, null, "Estamos revisando tu pago. Te avisaremos en unos minutos.");
        }

        confirmarOrden(pago, intento, orden, "api:" + orden.id(), "order.processed (respuesta de la API)", ahora);
        CitaResponse citaActualizada = marcarPagado(pago, orden.id(), ahora);
        return respuesta(pago, metodo, citaActualizada, "Pago aprobado");
    }

    /**
     * Aviso (webhook) de Mercado Pago (tema "order"). No se cree en el cuerpo: se toma el id de la orden y se consulta a
     * Mercado Pago con el Access Token. Es idempotente: cada estado de una orden se procesa una sola vez.
     *
     * @param idEnUrl el {@code data.id} que Mercado Pago también manda en la URL del aviso
     */
    @Transactional
    public ResultadoAviso procesarAvisoMercadoPago(String cuerpo, String idEnUrl) {
        if (!mercadoPago.configurada()) {
            return ResultadoAviso.IGNORADO;
        }
        JsonNode evento;
        try {
            evento = cuerpo == null || cuerpo.isBlank() ? JSON.createObjectNode() : JSON.readTree(cuerpo);
        } catch (Exception e) {
            evento = JSON.createObjectNode();
        }
        String tipo = evento.path("type").asText("order");
        if (!"order".equals(tipo)) {
            return ResultadoAviso.IGNORADO;
        }
        String ordenId = evento.path("data").path("id").asText("");
        if (ordenId.isBlank() && idEnUrl != null) {
            ordenId = idEnUrl.strip();
        }
        if (!ordenId.matches("ORD[A-Za-z0-9]{6,60}")) {
            return ResultadoAviso.IGNORADO;
        }

        Optional<Orden> consultada = mercadoPago.consultarOrden(ordenId); // no responde → 503 y Mercado Pago reintenta
        if (consultada.isEmpty()) {
            return ResultadoAviso.IGNORADO;
        }
        Orden orden = consultada.get();
        if (!orden.aprobada() && !orden.rechazada()) {
            return ResultadoAviso.IGNORADO; // todavía en curso: llegará otro aviso
        }
        String eventoId = ordenId + ":" + orden.estado() + (orden.detalle() == null ? "" : ":" + orden.detalle());
        if (eventoRepository.existsByPasarelaPagoAndEventoExternoId(PasarelaPago.MERCADO_PAGO, eventoId)) {
            return ResultadoAviso.YA_PROCESADO;
        }
        Optional<IntentoPago> buscado = uuid(orden.referenciaExterna()).flatMap(intentoRepository::findById);
        if (buscado.isEmpty()) {
            log.info("Aviso de Mercado Pago sobre una orden {} que no es de OdontoSystem", ordenId);
            return ResultadoAviso.IGNORADO;
        }
        IntentoPago intento = buscado.get();
        Pago pago = intento.getPago();
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        String accion = evento.path("action").asText("order." + orden.estado());

        if (pago.getEstado() != EstadoPago.PENDIENTE) {
            registrarAviso(PasarelaPago.MERCADO_PAGO, pago, intento, eventoId, accion, orden.json(),
                    EstadoWebhookPago.IGNORADO, ahora, "El pago ya estaba " + pago.getEstado());
            return ResultadoAviso.YA_PROCESADO;
        }
        if (orden.rechazada() || orden.montoPagadoCentimos() != centimos(pago.getMonto())) {
            registrarAviso(PasarelaPago.MERCADO_PAGO, pago, intento, eventoId, accion, orden.json(), EstadoWebhookPago.ERROR,
                    ahora, orden.rechazada() ? "Orden no aprobada: " + orden.detalle()
                            : "Monto distinto: " + orden.montoPagadoCentimos());
            if (orden.rechazada()) {
                intento.setEstado(EstadoIntentoPago.RECHAZADO);
                intento.setIdExterno(ordenId);
                intentoRepository.saveAndFlush(intento);
                pago.setEstado(EstadoPago.RECHAZADO);
                pagoRepository.saveAndFlush(pago);
            }
            return ResultadoAviso.RECHAZADO;
        }

        confirmarOrden(pago, intento, orden, eventoId, accion, ahora);
        Cita cita = pago.getCita();
        if (cita.getEstado() == EstadoCita.PENDIENTE_PAGO) {
            marcarPagado(pago, ordenId, ahora);
            return ResultadoAviso.CONFIRMADO;
        }
        // El cobro llegó cuando la reserva ya había vencido: se verifica el pago y se devuelve en el acto.
        pago.setEstado(EstadoPago.VERIFICADO);
        pago.setNumeroOperacion(ordenId);
        pago.setVerificadoEn(ahora);
        pagoRepository.saveAndFlush(pago);
        reembolsos.reembolsarDeposito(cita.getId(), "pago-tardio:" + pago.getId(),
                "El pago llegó cuando la reserva ya había vencido");
        return ResultadoAviso.CONFIRMADO_Y_DEVUELTO;
    }

    private void confirmarOrden(Pago pago, IntentoPago intento, Orden orden, String eventoId, String tipo,
                                OffsetDateTime ahora) {
        intento.setIdExterno(orden.id());
        intento.setEstado(EstadoIntentoPago.APROBADO);
        intento.setConfirmadoEn(ahora);
        intento.setMensajeError(null);
        intentoRepository.saveAndFlush(intento);
        registrarAviso(PasarelaPago.MERCADO_PAGO, pago, intento, eventoId, tipo, orden.json(),
                EstadoWebhookPago.PROCESADO, ahora, null);
    }

    // ==================================================================

    private Cita citaPendienteDePago(UUID pacienteId, UUID citaId) {
        Cita cita = citaRepository.findById(citaId)
                .filter(c -> c.getPaciente().getId().equals(pacienteId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cita no encontrada"));
        if (cita.getEstado() != EstadoCita.PENDIENTE_PAGO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cita no está pendiente de pago (" + cita.getEstado() + ")");
        }
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        if (cita.getCreadoEn() != null && cita.getCreadoEn().plus(CitaService.PLAZO_PAGO).isBefore(ahora)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Se venció el plazo de 15 minutos para pagar; reserva de nuevo");
        }
        if (pagoRepository.findFirstByCita_IdAndEstado(citaId, EstadoPago.PENDIENTE).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya hay un pago de esta cita en confirmación; espera unos minutos antes de volver a pagar");
        }
        return cita;
    }

    /** Pago VERIFICADO y la cita pasa a CONFIRMADA o PENDIENTE_CONFIRMACION. */
    private CitaResponse marcarPagado(Pago pago, String numeroOperacion, OffsetDateTime ahora) {
        pago.setEstado(EstadoPago.VERIFICADO);
        pago.setNumeroOperacion(numeroOperacion);
        pago.setVerificadoEn(ahora);
        pagoRepository.saveAndFlush(pago);

        Cita cita = pago.getCita();
        EstadoCita nuevo = cita.getOdontologo().isRequiereConfirmacionManual()
                ? EstadoCita.PENDIENTE_CONFIRMACION : EstadoCita.CONFIRMADA;
        return citaService.cambiarEstado(cita, cita.getPaciente().getId(), nuevo, null);
    }

    /**
     * Con Mercado Pago la autenticidad se verifica con la API (HTTPS y nuestro Access Token), no con una firma:
     * {@code firma_verificada} significa "confirmado por la pasarela".
     */
    private void registrarAviso(PasarelaPago procesador, Pago pago, IntentoPago intento, String eventoId, String tipo,
                                String payload, EstadoWebhookPago estado, OffsetDateTime ahora, String error) {
        boolean verificado = estado == EstadoWebhookPago.PROCESADO || estado == EstadoWebhookPago.IGNORADO;
        eventoRepository.saveAndFlush(EventoPagoWebhook.builder()
                .pago(pago).intentoPago(intento).pasarelaPago(procesador)
                .eventoExternoId(eventoId.length() > 150 ? eventoId.substring(0, 150) : eventoId)
                .tipoEvento(tipo.length() > 100 ? tipo.substring(0, 100) : tipo)
                .payloadJson(jsonValido(payload))
                .firmaRecibida("verificado consultando la API de Mercado Pago")
                .firmaVerificada(verificado)
                .estado(estado)
                .procesadoEn(verificado ? ahora : null)
                .mensajeError(error)
                .build());
    }

    /** Registra el aviso de la pasarela simulada; si la firma no cuadra, no se confirma nada (se deshace todo). */
    private void registrarAvisoSimulado(Pago pago, IntentoPago intento, Cobro cobro) {
        boolean firmaOk = pasarela.firmaValida(cobro.payload(), cobro.firma());
        eventoRepository.saveAndFlush(EventoPagoWebhook.builder()
                .pago(pago).intentoPago(intento).pasarelaPago(PasarelaPago.SIMULADA)
                .eventoExternoId(cobro.eventoId())
                .tipoEvento("charge.succeeded (simulado)")
                .payloadJson(cobro.payload())
                .firmaRecibida(cobro.firma())
                .firmaVerificada(firmaOk)
                .estado(firmaOk ? EstadoWebhookPago.PROCESADO : EstadoWebhookPago.ERROR)
                .procesadoEn(firmaOk ? OffsetDateTime.now(clock) : null)
                .mensajeError(firmaOk ? null : "Firma inválida")
                .build());
        if (!firmaOk) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "La confirmación del pago no es válida");
        }
    }

    private PagoResponse respuesta(Pago pago, MetodoPago metodo, CitaResponse cita, String mensaje) {
        return new PagoResponse(pago.getId(), pago.getEstado(), metodo, pago.getMonto(), pago.getNumeroOperacion(),
                pago.getVerificadoEn(), false, cita, mensaje);
    }

    static long centimos(BigDecimal monto) {
        return monto.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private static Optional<UUID> uuid(String texto) {
        try {
            return texto == null ? Optional.empty() : Optional.of(UUID.fromString(texto));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String jsonValido(String texto) {
        if (texto == null) return "{}";
        try {
            JSON.readTree(texto);
            return texto;
        } catch (Exception e) {
            return "{}";
        }
    }
}

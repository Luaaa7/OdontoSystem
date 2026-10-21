package com.odontosystem.OdontoSystem.citas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odontosystem.OdontoSystem.citas.CitaDtos.CitaResponse;
import com.odontosystem.OdontoSystem.citas.PasarelaCulqi.Cargo;
import com.odontosystem.OdontoSystem.citas.PasarelaCulqi.CulqiNoDisponible;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
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
 *   <li>{@code culqi}: el frontend tokeniza la tarjeta o el Yape con Culqi Checkout y manda el token; el backend
 *       crea el cargo con la llave secreta. La respuesta de la API (HTTPS + nuestra llave secreta) es la
 *       confirmación verificada. Si Culqi no responde, el pago queda PENDIENTE y lo cierra el aviso (webhook),
 *       que también se verifica consultando el cargo a Culqi.</li>
 * </ul>
 */
@Service
public class PagoService {

    private static final Logger log = LoggerFactory.getLogger(PagoService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** token: el que entrega Culqi Checkout ({@code tkn_…} tarjeta, {@code ype_…} Yape). Solo en modo culqi. */
    public record PagoRequest(@jakarta.validation.constraints.NotNull MetodoPago metodo,
                              @jakarta.validation.constraints.Size(max = 100) String token) {
        public PagoRequest(MetodoPago metodo) {
            this(metodo, null);
        }
    }

    /** estado: VERIFICADO (pagado), RECHAZADO (puede reintentar con otro medio) o PENDIENTE (se confirma en minutos). */
    public record PagoResponse(UUID pagoId, EstadoPago estado, MetodoPago metodo, BigDecimal monto,
                               String numeroOperacion, OffsetDateTime verificadoEn, boolean simulado,
                               CitaResponse cita, String mensaje) {}

    /** Lo que se hizo con un aviso de Culqi (para el log y la respuesta del webhook). */
    public enum ResultadoAviso { CONFIRMADO, CONFIRMADO_Y_DEVUELTO, RECHAZADO, YA_PROCESADO, IGNORADO }

    private final CitaRepository citaRepository;
    private final PagoRepository pagoRepository;
    private final IntentoPagoRepository intentoRepository;
    private final EventoPagoWebhookRepository eventoRepository;
    private final CitaService citaService;
    private final PasarelaSimulada pasarela;
    private final PasarelaCulqi culqi;
    private final ReembolsoService reembolsos;
    private final Clock clock;
    private final String modo;

    public PagoService(CitaRepository citaRepository, PagoRepository pagoRepository,
                       IntentoPagoRepository intentoRepository, EventoPagoWebhookRepository eventoRepository,
                       CitaService citaService, PasarelaSimulada pasarela, PasarelaCulqi culqi,
                       ReembolsoService reembolsos, Clock clock,
                       @Value("${app.pagos.modo:simulado}") String modo) {
        this.citaRepository = citaRepository;
        this.pagoRepository = pagoRepository;
        this.intentoRepository = intentoRepository;
        this.eventoRepository = eventoRepository;
        this.citaService = citaService;
        this.pasarela = pasarela;
        this.culqi = culqi;
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
        boolean conCulqi = "culqi".equalsIgnoreCase(modo);
        if (!conCulqi && !"simulado".equalsIgnoreCase(modo)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Los pagos en línea aún no están habilitados");
        }
        if (conCulqi) {
            validarParaCulqi(metodo, token);
        }
        Cita cita = citaPendienteDePago(pacienteId, citaId);
        OffsetDateTime ahora = OffsetDateTime.now(clock);

        BigDecimal monto = cita.getMontoDepositoRequerido();
        Pago pago = pagoRepository.saveAndFlush(Pago.builder()
                .cita(cita).metodoPago(metodo).pasarelaPago(PasarelaPago.CULQI)
                .monto(monto).estado(EstadoPago.PENDIENTE).build());
        IntentoPago intento = intentoRepository.saveAndFlush(IntentoPago.builder()
                .pago(pago).pasarelaPago(PasarelaPago.CULQI)
                .claveIdempotencia(citaId + ":" + UUID.randomUUID())
                .monto(monto).estado(EstadoIntentoPago.PENDIENTE).build());

        if (conCulqi) {
            return cobrarConCulqi(cita, pago, intento, metodo, token, ahora);
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
    // Culqi
    // ==================================================================

    private void validarParaCulqi(MetodoPago metodo, String token) {
        if (!culqi.configurada()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Los pagos en línea no están disponibles en este momento");
        }
        if (metodo == MetodoPago.PLIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Plin todavía no está disponible; paga con tarjeta o Yape");
        }
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el token de Culqi: abre el formulario de pago y vuelve a intentarlo");
        }
        String esperado = metodo == MetodoPago.YAPE ? "ype_" : "tkn_";
        if (!token.strip().startsWith(esperado)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El token no corresponde al medio de pago elegido (" + metodo + ")");
        }
    }

    private PagoResponse cobrarConCulqi(Cita cita, Pago pago, IntentoPago intento, MetodoPago metodo, String token,
                                        OffsetDateTime ahora) {
        long centimos = centimos(pago.getMonto());
        Map<String, String> metadata = Map.of(
                "cita_id", cita.getId().toString(),
                "pago_id", pago.getId().toString(),
                "intento_id", intento.getId().toString());
        Cargo cargo;
        try {
            cargo = culqi.cobrar(token.strip(), centimos, cita.getPaciente().getCorreo(),
                    "Deposito de cita OdontoSystem", metadata);
        } catch (CulqiNoDisponible e) {
            // No sabemos si cobró: el pago queda PENDIENTE y lo cierra el aviso de Culqi.
            log.warn("Culqi no respondió al cobrar el pago {}: {}", pago.getId(), e.getMessage());
            intento.setMensajeError("Culqi no respondió: " + e.getMessage());
            intentoRepository.saveAndFlush(intento);
            return respuesta(pago, metodo, null,
                    "Estamos confirmando tu pago con el banco. Te avisaremos en unos minutos; no vuelvas a pagar.");
        }

        if (!cargo.aprobado()) {
            intento.setIdExterno(cargo.id());
            intento.setEstado(EstadoIntentoPago.RECHAZADO);
            intento.setMensajeError(cargo.codigo() + ": " + cargo.mensajeUsuario());
            intentoRepository.saveAndFlush(intento);
            pago.setEstado(EstadoPago.RECHAZADO);
            pagoRepository.saveAndFlush(pago);
            return respuesta(pago, metodo, null, cargo.mensajeUsuario());
        }
        if (cargo.montoCentimos() != centimos) {
            // No debería pasar nunca: se cobró otro monto. No se confirma la cita.
            log.error("Culqi cobró {} céntimos para el pago {} (esperado {})", cargo.montoCentimos(), pago.getId(), centimos);
            intento.setIdExterno(cargo.id());
            intento.setMensajeError("Monto distinto: " + cargo.montoCentimos());
            intentoRepository.saveAndFlush(intento);
            return respuesta(pago, metodo, null, "Estamos revisando tu pago. Te avisaremos en unos minutos.");
        }

        confirmarCargo(pago, intento, cargo, "api:" + cargo.id(), "charge.creation.succeeded (respuesta de la API)", ahora);
        CitaResponse citaActualizada = marcarPagado(pago, cargo.id(), ahora);
        return respuesta(pago, metodo, citaActualizada, cargo.mensajeUsuario() == null ? "Pago aprobado" : cargo.mensajeUsuario());
    }

    /**
     * Aviso (webhook) de Culqi. No se cree en el cuerpo: se toma el id del cargo y se consulta a Culqi con la llave
     * secreta. Así nadie puede confirmar un pago mandando un aviso falso. Es idempotente.
     */
    @Transactional
    public ResultadoAviso procesarAvisoCulqi(String cuerpo) {
        if (!culqi.configurada()) {
            return ResultadoAviso.IGNORADO;
        }
        JsonNode evento;
        try {
            evento = JSON.readTree(cuerpo);
        } catch (Exception e) {
            return ResultadoAviso.IGNORADO;
        }
        String eventoId = evento.path("id").asText("");
        String tipo = evento.path("type").asText("");
        if (eventoId.isBlank() || !tipo.startsWith("charge.")) {
            return ResultadoAviso.IGNORADO;
        }
        if (eventoRepository.existsByPasarelaPagoAndEventoExternoId(PasarelaPago.CULQI, eventoId)) {
            return ResultadoAviso.YA_PROCESADO;
        }
        String cargoId = idCargo(evento.path("data"));
        if (cargoId == null) {
            return ResultadoAviso.IGNORADO;
        }

        Optional<Cargo> consultado = culqi.consultarCargo(cargoId); // CulqiNoDisponible → el webhook responde 503 y Culqi reintenta
        if (consultado.isEmpty()) {
            return ResultadoAviso.IGNORADO;
        }
        Cargo cargo = consultado.get();
        Optional<IntentoPago> buscado = uuid(cargo.metadata().get("intento_id")).flatMap(intentoRepository::findById);
        if (buscado.isEmpty()) {
            log.info("Aviso de Culqi {} sobre un cargo {} que no es de OdontoSystem", eventoId, cargoId);
            return ResultadoAviso.IGNORADO;
        }
        IntentoPago intento = buscado.get();
        Pago pago = intento.getPago();
        OffsetDateTime ahora = OffsetDateTime.now(clock);

        if (pago.getEstado() != EstadoPago.PENDIENTE) {
            registrarAviso(pago, intento, eventoId, tipo, cuerpo, EstadoWebhookPago.IGNORADO, ahora,
                    "El pago ya estaba " + pago.getEstado());
            return ResultadoAviso.YA_PROCESADO;
        }
        if (!cargo.aprobado() || cargo.montoCentimos() != centimos(pago.getMonto())) {
            registrarAviso(pago, intento, eventoId, tipo, cuerpo, EstadoWebhookPago.ERROR, ahora,
                    cargo.aprobado() ? "Monto distinto: " + cargo.montoCentimos() : "Cargo no aprobado: " + cargo.codigo());
            if (!cargo.aprobado()) {
                intento.setEstado(EstadoIntentoPago.RECHAZADO);
                intento.setIdExterno(cargoId);
                intentoRepository.saveAndFlush(intento);
                pago.setEstado(EstadoPago.RECHAZADO);
                pagoRepository.saveAndFlush(pago);
            }
            return ResultadoAviso.RECHAZADO;
        }

        confirmarCargo(pago, intento, cargo, eventoId, tipo, ahora);
        Cita cita = pago.getCita();
        if (cita.getEstado() == EstadoCita.PENDIENTE_PAGO) {
            marcarPagado(pago, cargoId, ahora);
            return ResultadoAviso.CONFIRMADO;
        }
        // El cobro llegó cuando la reserva ya había vencido: se verifica el pago y se devuelve en el acto.
        pago.setEstado(EstadoPago.VERIFICADO);
        pago.setNumeroOperacion(cargoId);
        pago.setVerificadoEn(ahora);
        pagoRepository.saveAndFlush(pago);
        reembolsos.reembolsarDeposito(cita.getId(), "pago-tardio:" + pago.getId(),
                "El pago llegó cuando la reserva ya había vencido");
        return ResultadoAviso.CONFIRMADO_Y_DEVUELTO;
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

    /** Intento APROBADO + aviso PROCESADO (verificado), que es lo que la BD exige antes de VERIFICADO. */
    private void confirmarCargo(Pago pago, IntentoPago intento, Cargo cargo, String eventoId, String tipo,
                                OffsetDateTime ahora) {
        intento.setIdExterno(cargo.id());
        intento.setEstado(EstadoIntentoPago.APROBADO);
        intento.setConfirmadoEn(ahora);
        intento.setMensajeError(null);
        intentoRepository.saveAndFlush(intento);
        registrarAviso(pago, intento, eventoId, tipo, cargo.json(), EstadoWebhookPago.PROCESADO, ahora, null);
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
     * Para Culqi la autenticidad se verifica con la API (HTTPS y nuestra llave secreta), no con una firma:
     * {@code firma_verificada} significa "confirmado por la pasarela".
     */
    private void registrarAviso(Pago pago, IntentoPago intento, String eventoId, String tipo, String payload,
                                EstadoWebhookPago estado, OffsetDateTime ahora, String error) {
        boolean verificado = estado == EstadoWebhookPago.PROCESADO || estado == EstadoWebhookPago.IGNORADO;
        eventoRepository.saveAndFlush(EventoPagoWebhook.builder()
                .pago(pago).intentoPago(intento).pasarelaPago(PasarelaPago.CULQI)
                .eventoExternoId(eventoId)
                .tipoEvento(tipo.length() > 100 ? tipo.substring(0, 100) : tipo)
                .payloadJson(jsonValido(payload))
                .firmaRecibida("verificado consultando la API de Culqi")
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
                .pago(pago).intentoPago(intento).pasarelaPago(PasarelaPago.CULQI)
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

    /** El cargo puede venir como objeto o como texto JSON dentro de "data". */
    private static String idCargo(JsonNode data) {
        try {
            JsonNode cargo = data.isTextual() ? JSON.readTree(data.asText()) : data;
            String id = cargo.path("id").asText("");
            return id.startsWith("chr_") ? id : null;
        } catch (Exception e) {
            return null;
        }
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

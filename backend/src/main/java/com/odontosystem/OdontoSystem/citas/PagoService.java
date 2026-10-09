package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.CitaDtos.CitaResponse;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pago del depósito de una cita (RF04). Flujo:
 * <ol>
 *   <li>Se crea el pago PENDIENTE y un intento de cobro con clave de idempotencia.</li>
 *   <li>La pasarela aprueba y manda un aviso firmado.</li>
 *   <li>Se verifica la firma y se registra el aviso como PROCESADO.</li>
 *   <li>Recién entonces el pago pasa a VERIFICADO (la BD lo exige) y la cita a CONFIRMADA,
 *       o a PENDIENTE_CONFIRMACION si el odontólogo aprueba sus citas a mano (RF11).</li>
 * </ol>
 * Hoy la pasarela es {@link PasarelaSimulada}; con Culqi solo cambia el paso 2.
 */
@Service
public class PagoService {

    public record PagoRequest(@jakarta.validation.constraints.NotNull MetodoPago metodo) {}

    public record PagoResponse(UUID pagoId, EstadoPago estado, MetodoPago metodo, BigDecimal monto,
                               String numeroOperacion, OffsetDateTime verificadoEn, boolean simulado,
                               CitaResponse cita) {}

    private final CitaRepository citaRepository;
    private final PagoRepository pagoRepository;
    private final IntentoPagoRepository intentoRepository;
    private final EventoPagoWebhookRepository eventoRepository;
    private final CitaService citaService;
    private final PasarelaSimulada pasarela;
    private final Clock clock;
    private final String modo;

    public PagoService(CitaRepository citaRepository, PagoRepository pagoRepository,
                       IntentoPagoRepository intentoRepository, EventoPagoWebhookRepository eventoRepository,
                       CitaService citaService, PasarelaSimulada pasarela, Clock clock,
                       @Value("${app.pagos.modo:simulado}") String modo) {
        this.citaRepository = citaRepository;
        this.pagoRepository = pagoRepository;
        this.intentoRepository = intentoRepository;
        this.eventoRepository = eventoRepository;
        this.citaService = citaService;
        this.pasarela = pasarela;
        this.clock = clock;
        this.modo = modo;
    }

    @Transactional
    public PagoResponse pagarDeposito(UUID pacienteId, UUID citaId, MetodoPago metodo) {
        if (!"simulado".equalsIgnoreCase(modo)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Los pagos en línea aún no están habilitados");
        }
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

        BigDecimal monto = cita.getMontoDepositoRequerido();
        Pago pago = pagoRepository.saveAndFlush(Pago.builder()
                .cita(cita).metodoPago(metodo).pasarelaPago(PasarelaPago.CULQI)
                .monto(monto).estado(EstadoPago.PENDIENTE).build());
        IntentoPago intento = intentoRepository.saveAndFlush(IntentoPago.builder()
                .pago(pago).pasarelaPago(PasarelaPago.CULQI)
                .claveIdempotencia(citaId + ":" + UUID.randomUUID())
                .monto(monto).estado(EstadoIntentoPago.PENDIENTE).build());

        Cobro cobro = pasarela.cobrar(intento.getId(), monto, metodo.name());
        intento.setIdExterno(cobro.idExterno());
        intento.setEstado(EstadoIntentoPago.APROBADO);
        intento.setConfirmadoEn(ahora);
        intentoRepository.saveAndFlush(intento);

        registrarAviso(pago, intento, cobro);

        pago.setEstado(EstadoPago.VERIFICADO);
        pago.setNumeroOperacion(cobro.idExterno());
        pago.setVerificadoEn(ahora);
        pagoRepository.saveAndFlush(pago);

        EstadoCita nuevo = cita.getOdontologo().isRequiereConfirmacionManual()
                ? EstadoCita.PENDIENTE_CONFIRMACION : EstadoCita.CONFIRMADA;
        CitaResponse citaActualizada = citaService.cambiarEstado(cita, pacienteId, nuevo, null);
        return new PagoResponse(pago.getId(), pago.getEstado(), metodo, monto, pago.getNumeroOperacion(),
                pago.getVerificadoEn(), true, citaActualizada);
    }

    /** Registra el aviso de la pasarela; si la firma no cuadra, no se confirma nada (se deshace toda la operación). */
    private void registrarAviso(Pago pago, IntentoPago intento, Cobro cobro) {
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
}

package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.EstadoReembolso;
import com.odontosystem.OdontoSystem.entity.Pago;
import com.odontosystem.OdontoSystem.entity.Reembolso;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.PagoRepository;
import com.odontosystem.OdontoSystem.repository.ReembolsoRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * RF15: devolución del depósito.
 * <ul>
 *   <li>Al cancelar con reembolso (paciente con 12 h o más, o cualquier cancelación del odontólogo).</li>
 *   <li>Al aprobarse una apelación por inasistencia (la ausencia estaba justificada).</li>
 * </ul>
 * Es idempotente: cada motivo usa una clave propia ("cancelacion:&lt;cita&gt;") y la BD no permite repetirla.
 * Con la pasarela simulada el reembolso queda REEMBOLSADO en el acto. Con Culqi se pide la devolución a su API
 * ({@code POST /refunds}) y queda REEMBOLSADO si Culqi la acepta; si Culqi no responde queda SOLICITADO.
 */
@Service
public class ReembolsoService {

    private static final Logger log = LoggerFactory.getLogger(ReembolsoService.class);

    public record ReembolsoResponse(UUID id, EstadoReembolso estado, BigDecimal monto, String motivo,
                                    String idExterno, OffsetDateTime solicitadoEn, OffsetDateTime procesadoEn,
                                    boolean simulado) {}

    private final PagoRepository pagoRepository;
    private final ReembolsoRepository reembolsoRepository;
    private final CitaRepository citaRepository;
    private final PasarelaSimulada pasarela;
    private final PasarelaCulqi culqi;
    private final Clock clock;
    private final String modo;

    public ReembolsoService(PagoRepository pagoRepository, ReembolsoRepository reembolsoRepository,
                            CitaRepository citaRepository, PasarelaSimulada pasarela, PasarelaCulqi culqi, Clock clock,
                            @Value("${app.pagos.modo:simulado}") String modo) {
        this.pagoRepository = pagoRepository;
        this.reembolsoRepository = reembolsoRepository;
        this.citaRepository = citaRepository;
        this.pasarela = pasarela;
        this.culqi = culqi;
        this.clock = clock;
        this.modo = modo;
    }

    /**
     * Devuelve el depósito pagado de la cita. Si la cita no tiene un pago VERIFICADO (por ejemplo, nunca se
     * pagó) no hace nada. Corre dentro de la transacción de quien la llama: si algo falla, no se cancela nada.
     */
    @Transactional
    public Optional<ReembolsoResponse> reembolsarDeposito(UUID citaId, String clave, String motivo) {
        Optional<Pago> pagado = pagoRepository.findFirstByCita_IdAndEstado(citaId, EstadoPago.VERIFICADO);
        if (pagado.isEmpty()) {
            log.info("La cita {} no tiene un pago verificado: no hay depósito que devolver", citaId);
            return Optional.empty();
        }
        Pago pago = pagado.get();
        Optional<Reembolso> existente = reembolsoRepository.findByPago_IdAndClaveIdempotencia(pago.getId(), clave);
        if (existente.isPresent()) {
            return existente.map(this::aRespuesta);
        }
        Reembolso reembolso = reembolsoRepository.saveAndFlush(Reembolso.builder()
                .pago(pago)
                .pasarelaPago(pago.getPasarelaPago())
                .claveIdempotencia(clave)
                .monto(pago.getMonto())
                .motivo(motivo)
                .estado(EstadoReembolso.SOLICITADO)
                .build());

        String operacion = pago.getNumeroOperacion();
        boolean pagoSimulado = operacion != null && operacion.startsWith("sim_");
        if (esSimulado() || pagoSimulado) {
            reembolso.setIdExterno(pasarela.reembolsar(reembolso.getId(), pago.getMonto()));
            reembolso.setEstado(EstadoReembolso.REEMBOLSADO);
            reembolso.setProcesadoEn(OffsetDateTime.now(clock));
            reembolso = reembolsoRepository.saveAndFlush(reembolso); // el trigger pasa el pago a REEMBOLSADO
        } else {
            reembolso = pedirACulqi(reembolso);
        }
        return Optional.of(aRespuesta(reembolso));
    }

    /**
     * Tarea programada: vuelve a pedir a Culqi las devoluciones que quedaron SOLICITADO porque Culqi no respondió.
     * Devuelve cuántas quedaron REEMBOLSADO.
     */
    @Transactional
    public int reintentarSolicitados() {
        if (!culqi.configurada()) {
            return 0;
        }
        int hechos = 0;
        for (Reembolso r : reembolsoRepository.findByEstado(EstadoReembolso.SOLICITADO)) {
            if (pedirACulqi(r).getEstado() == EstadoReembolso.REEMBOLSADO) {
                hechos++;
            }
        }
        return hechos;
    }

    /** Pide la devolución a Culqi; si Culqi no responde, el reembolso queda SOLICITADO para el siguiente intento. */
    private Reembolso pedirACulqi(Reembolso reembolso) {
        Pago pago = reembolso.getPago();
        String operacion = pago.getNumeroOperacion();
        if (!culqi.configurada() || operacion == null || !operacion.startsWith("chr_")) {
            return reembolso;
        }
        try {
            reembolso.setIdExterno(culqi.reembolsar(operacion, PagoService.centimos(reembolso.getMonto())));
            reembolso.setEstado(EstadoReembolso.REEMBOLSADO);
            reembolso.setProcesadoEn(OffsetDateTime.now(clock));
            return reembolsoRepository.saveAndFlush(reembolso); // el trigger pasa el pago a REEMBOLSADO
        } catch (PasarelaCulqi.CulqiNoDisponible e) {
            log.warn("Culqi no aceptó la devolución del pago {}: queda SOLICITADO ({})", pago.getId(), e.getMessage());
            return reembolso;
        }
    }

    /** Reembolsos de una cita; los ve el paciente o el odontólogo de la cita. */
    @Transactional(readOnly = true)
    public List<ReembolsoResponse> deCita(UUID usuarioId, UUID citaId) {
        citaRepository.findById(citaId)
                .filter(c -> c.getPaciente().getId().equals(usuarioId) || c.getOdontologo().getUsuarioId().equals(usuarioId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cita no encontrada"));
        return reembolsoRepository.findByPago_Cita_IdOrderBySolicitadoEnDesc(citaId).stream().map(this::aRespuesta).toList();
    }

    private boolean esSimulado() {
        return "simulado".equalsIgnoreCase(modo);
    }

    private ReembolsoResponse aRespuesta(Reembolso r) {
        return new ReembolsoResponse(r.getId(), r.getEstado(), r.getMonto(), r.getMotivo(), r.getIdExterno(),
                r.getSolicitadoEn(), r.getProcesadoEn(), r.getIdExterno() != null && r.getIdExterno().startsWith("sim_"));
    }
}

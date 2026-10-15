package com.odontosystem.OdontoSystem.liquidaciones;

import com.odontosystem.OdontoSystem.citas.ParametrosSistema;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoApelacion;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoLiquidacion;
import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.EstadoReclamo;
import com.odontosystem.OdontoSystem.entity.Liquidacion;
import com.odontosystem.OdontoSystem.entity.Pago;
import com.odontosystem.OdontoSystem.repository.ApelacionRepository;
import com.odontosystem.OdontoSystem.repository.LiquidacionRepository;
import com.odontosystem.OdontoSystem.repository.PagoRepository;
import com.odontosystem.OdontoSystem.repository.ReclamoRepository;
import com.odontosystem.OdontoSystem.repository.ReporteAusenciaRepository;
import com.odontosystem.OdontoSystem.entity.EstadoReporteAusencia;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Destino del depósito: la plataforma lo tiene en custodia y luego se lo paga al odontólogo o se lo devuelve
 * al paciente. Nunca se queda "congelado" sin destino.
 * <ul>
 *   <li>Se le paga al odontólogo si la cita terminó COMPLETADA, INASISTENCIA_PENALIZADA o
 *       CANCELADA_SIN_REEMBOLSO (canceló con menos de 12 h): compensa el turno que reservó.</li>
 *   <li>Se paga HORAS_RETENCION_LIQUIDACION (72 h) después del fin de la cita; en una inasistencia se espera
 *       además a que venza el plazo de apelación (7 días).</li>
 *   <li>Si el paciente tiene un reclamo abierto, una apelación pendiente o un reporte de ausencia en revisión sobre
 *       esa cita, el pago se retiene.</li>
 *   <li>Si el depósito termina devuelto al paciente (apelación aprobada, el odontólogo lo devuelve al atender un
 *       reclamo), la liquidación pasa a CANCELADA.</li>
 * </ul>
 * La transferencia es simulada (referencia "sim_trf_…"); en producción la haría la pasarela o un banco.
 */
@Service
@RequiredArgsConstructor
public class LiquidacionService {

    public static final String HORAS_RETENCION = "HORAS_RETENCION_LIQUIDACION";
    static final Duration PLAZO_APELACION = Duration.ofDays(7);
    static final Set<EstadoCita> COBRABLES = Set.of(EstadoCita.COMPLETADA, EstadoCita.INASISTENCIA_PENALIZADA,
            EstadoCita.CANCELADA_SIN_REEMBOLSO);
    private static final Set<EstadoReclamo> RECLAMO_ABIERTO = Set.of(EstadoReclamo.ABIERTO, EstadoReclamo.EN_PROCESO);

    public record LiquidacionResponse(UUID id, UUID citaId, String paciente, String servicio, OffsetDateTime fechaCita,
                                      EstadoCita estadoCita, BigDecimal monto, EstadoLiquidacion estado,
                                      String retenidaPor, OffsetDateTime pagarDesde, String referenciaPago,
                                      OffsetDateTime liquidadoEn) {}

    public record MisIngresos(BigDecimal enCustodia, BigDecimal pagado, BigDecimal devueltoAPacientes,
                              List<LiquidacionResponse> liquidaciones) {}

    public record ResultadoProceso(int registradas, int pagadas, int canceladas) {}

    private final LiquidacionRepository liquidacionRepository;
    private final PagoRepository pagoRepository;
    private final ReclamoRepository reclamoRepository;
    private final ApelacionRepository apelacionRepository;
    private final ReporteAusenciaRepository reporteRepository;
    private final ParametrosSistema parametros;
    private final Clock clock;

    /** Tarea programada: registra los depósitos nuevos y paga los que ya cumplieron su plazo. */
    @Transactional
    public ResultadoProceso procesar() {
        int registradas = registrarPendientes();
        int pagadas = 0;
        int canceladas = 0;
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        for (Liquidacion l : liquidacionRepository.enEstado(EstadoLiquidacion.PENDIENTE)) {
            Cita c = l.getCita();
            if (c == null || pagoRepository.findFirstByCita_IdAndEstado(c.getId(), EstadoPago.VERIFICADO).isEmpty()) {
                cancelar(l, "El depósito se devolvió al paciente");
                canceladas++;
            } else if (retenidaPor(c) == null && !ahora.isBefore(pagarDesde(c))) {
                l.setEstado(EstadoLiquidacion.LIQUIDADA);
                l.setReferenciaPago("sim_trf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
                l.setLiquidadoEn(ahora);
                liquidacionRepository.save(l);
                pagadas++;
            }
        }
        return new ResultadoProceso(registradas, pagadas, canceladas);
    }

    /** Cuando el depósito se devuelve al paciente: si aún no se pagó al odontólogo, se cancela. */
    @Transactional
    public void cancelarDeCita(UUID citaId, String motivo) {
        liquidacionRepository.findFirstByCita_Id(citaId)
                .filter(l -> l.getEstado() == EstadoLiquidacion.PENDIENTE)
                .ifPresent(l -> cancelar(l, motivo));
    }

    /** true si el depósito de esa cita ya se transfirió al odontólogo (ya no se puede devolver desde aquí). */
    @Transactional(readOnly = true)
    public boolean yaPagada(UUID citaId) {
        return liquidacionRepository.findFirstByCita_Id(citaId)
                .map(l -> l.getEstado() == EstadoLiquidacion.LIQUIDADA)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public MisIngresos delOdontologo(UUID odontologoId) {
        List<LiquidacionResponse> lista = liquidacionRepository.delOdontologo(odontologoId).stream()
                .map(this::aRespuesta).toList();
        return new MisIngresos(suma(lista, EstadoLiquidacion.PENDIENTE), suma(lista, EstadoLiquidacion.LIQUIDADA),
                suma(lista, EstadoLiquidacion.CANCELADA), lista);
    }

    // ------------------------------------------------------------------

    int registrarPendientes() {
        List<Pago> pagos = liquidacionRepository.depositosSinLiquidar(COBRABLES);
        for (Pago p : pagos) {
            liquidacionRepository.save(Liquidacion.builder()
                    .odontologo(p.getCita().getOdontologo())
                    .cita(p.getCita())
                    .monto(p.getMonto())
                    .creadoEn(OffsetDateTime.now(clock))
                    .build());
        }
        return pagos.size();
    }

    OffsetDateTime pagarDesde(Cita c) {
        OffsetDateTime fin = c.getFinProgramado() != null ? c.getFinProgramado() : c.getInicioProgramado();
        OffsetDateTime desde = fin.plusHours(parametros.entero(HORAS_RETENCION, 72));
        if (c.getEstado() == EstadoCita.INASISTENCIA_PENALIZADA) {
            OffsetDateTime venceApelacion = c.getInicioProgramado().plus(PLAZO_APELACION);
            if (venceApelacion.isAfter(desde)) desde = venceApelacion;
        }
        return desde;
    }

    /** Motivo por el que se retiene el pago, o null si no hay nada pendiente. */
    String retenidaPor(Cita c) {
        if (reclamoRepository.existsByCita_IdAndEstadoIn(c.getId(), RECLAMO_ABIERTO)) {
            return "Reclamo del paciente sin resolver";
        }
        if (apelacionRepository.existsByCita_IdAndEstado(c.getId(), EstadoApelacion.PENDIENTE)) {
            return "Apelación del paciente pendiente";
        }
        if (reporteRepository.existsByCita_IdAndEstado(c.getId(), EstadoReporteAusencia.PENDIENTE)) {
            return "El paciente reportó que no lo atendiste (en revisión)";
        }
        return null;
    }

    private void cancelar(Liquidacion l, String motivo) {
        l.setEstado(EstadoLiquidacion.CANCELADA);
        l.setMotivoFallo(motivo);
        liquidacionRepository.save(l);
    }

    private LiquidacionResponse aRespuesta(Liquidacion l) {
        Cita c = l.getCita();
        boolean pendiente = l.getEstado() == EstadoLiquidacion.PENDIENTE && c != null;
        return new LiquidacionResponse(l.getId(), c != null ? c.getId() : null,
                c != null && c.getPaciente() != null ? c.getPaciente().getNombreCompleto() : null,
                c != null && c.getServicio() != null ? c.getServicio().getTitulo() : null,
                c != null ? c.getInicioProgramado() : null, c != null ? c.getEstado() : null,
                l.getMonto(), l.getEstado(),
                pendiente ? retenidaPor(c) : (l.getEstado() == EstadoLiquidacion.CANCELADA ? l.getMotivoFallo() : null),
                pendiente ? pagarDesde(c) : null, l.getReferenciaPago(), l.getLiquidadoEn());
    }

    private static BigDecimal suma(List<LiquidacionResponse> lista, EstadoLiquidacion estado) {
        return lista.stream().filter(l -> l.estado() == estado).map(LiquidacionResponse::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

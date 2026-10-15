package com.odontosystem.OdontoSystem.liquidaciones;

import com.odontosystem.OdontoSystem.apelaciones.ApelacionService;
import com.odontosystem.OdontoSystem.citas.AsistenciaService;
import com.odontosystem.OdontoSystem.reclamos.ReclamoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cada hora, en este orden:
 * <ol>
 *   <li>Plazos vencidos: apelaciones sin revisar en 7 días y reclamos sin resolver en 15 días hábiles se resuelven
 *       a favor del paciente (así el odontólogo no puede retener el depósito ignorándolos).</li>
 *   <li>Asistencia: reportes "el odontólogo no llegó" sin respuesta en 48 h y citas que nadie cerró en 72 h.</li>
 *   <li>Liquidaciones: registra los depósitos en custodia y paga al odontólogo los que ya cumplieron su plazo.</li>
 * </ol>
 */
@Component
public class TareasLiquidaciones {

    private static final Logger log = LoggerFactory.getLogger(TareasLiquidaciones.class);

    private final LiquidacionService liquidacionService;
    private final ApelacionService apelacionService;
    private final ReclamoService reclamoService;
    private final AsistenciaService asistenciaService;

    public TareasLiquidaciones(LiquidacionService liquidacionService, ApelacionService apelacionService,
                               ReclamoService reclamoService, AsistenciaService asistenciaService) {
        this.liquidacionService = liquidacionService;
        this.apelacionService = apelacionService;
        this.reclamoService = reclamoService;
        this.asistenciaService = asistenciaService;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void procesar() {
        try {
            int apelaciones = apelacionService.aprobarSinRevisar();
            int reclamos = reclamoService.resolverVencidos();
            if (apelaciones + reclamos > 0) {
                log.info("Plazos vencidos: {} apelaciones aprobadas y {} reclamos resueltos a favor del paciente",
                        apelaciones, reclamos);
            }
        } catch (RuntimeException e) {
            log.error("Falló la revisión de plazos de apelaciones y reclamos", e);
        }
        try {
            AsistenciaService.ResultadoTarea a = asistenciaService.procesarVencidos();
            if (a.confirmados() + a.rechazados() + a.enReclamo() + a.citasCerradas() > 0) {
                log.info("Asistencia: {} ausencias confirmadas, {} reportes rechazados, {} a reclamo, {} citas cerradas",
                        a.confirmados(), a.rechazados(), a.enReclamo(), a.citasCerradas());
            }
        } catch (RuntimeException e) {
            log.error("Falló la revisión de reportes de asistencia", e);
        }
        try {
            LiquidacionService.ResultadoProceso r = liquidacionService.procesar();
            if (r.registradas() + r.pagadas() + r.canceladas() > 0) {
                log.info("Liquidaciones: {} en custodia, {} pagadas al odontólogo, {} canceladas por devolución",
                        r.registradas(), r.pagadas(), r.canceladas());
            }
        } catch (RuntimeException e) {
            log.error("Falló la tarea de liquidaciones", e);
        }
    }
}

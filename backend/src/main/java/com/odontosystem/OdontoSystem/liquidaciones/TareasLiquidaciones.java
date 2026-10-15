package com.odontosystem.OdontoSystem.liquidaciones;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cada hora: registra los depósitos en custodia y paga al odontólogo los que ya cumplieron su plazo. */
@Component
public class TareasLiquidaciones {

    private static final Logger log = LoggerFactory.getLogger(TareasLiquidaciones.class);

    private final LiquidacionService liquidacionService;

    public TareasLiquidaciones(LiquidacionService liquidacionService) {
        this.liquidacionService = liquidacionService;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void procesar() {
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

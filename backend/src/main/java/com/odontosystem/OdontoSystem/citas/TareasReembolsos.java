package com.odontosystem.OdontoSystem.citas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cada 30 minutos: reintenta en Culqi las devoluciones que quedaron pendientes porque Culqi no respondió (RF15). */
@Component
public class TareasReembolsos {

    private static final Logger log = LoggerFactory.getLogger(TareasReembolsos.class);

    private final ReembolsoService reembolsos;

    public TareasReembolsos(ReembolsoService reembolsos) {
        this.reembolsos = reembolsos;
    }

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 240_000)
    public void reintentar() {
        try {
            int n = reembolsos.reintentarSolicitados();
            if (n > 0) {
                log.info("Se completaron {} devoluciones pendientes en Culqi", n);
            }
        } catch (RuntimeException e) {
            log.error("Falló el reintento de devoluciones", e);
        }
    }
}

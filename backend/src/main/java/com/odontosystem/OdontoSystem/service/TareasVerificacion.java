package com.odontosystem.OdontoSystem.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cada 30 minutos: reintenta la verificación COP de los odontólogos cuyo intento anterior falló por la red (RF09). */
@Component
public class TareasVerificacion {

    private static final Logger log = LoggerFactory.getLogger(TareasVerificacion.class);

    private final VerificacionColegiaturaService verificacion;

    public TareasVerificacion(VerificacionColegiaturaService verificacion) {
        this.verificacion = verificacion;
    }

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 180_000)
    public void reintentar() {
        try {
            int n = verificacion.reintentarPendientes();
            if (n > 0) {
                log.info("Se reintentó la verificación COP de {} odontólogos", n);
            }
            int observados = verificacion.revisarObservadosConReniec();
            if (observados > 0) {
                log.info("Se revisaron {} odontólogos OBSERVADO con RENIEC (Factiliza)", observados);
            }
        } catch (RuntimeException e) {
            log.error("Falló el reintento de verificación COP", e);
        }
    }
}

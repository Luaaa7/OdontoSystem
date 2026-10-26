package com.odontosystem.OdontoSystem.notificaciones;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** System Jobs de RF10. Se apagan con RECORDATORIOS_ACTIVOS=false. */
@Component
@ConditionalOnProperty(name = "app.recordatorios.activos", havingValue = "true", matchIfMissing = true)
public class TareasRecordatorios {

    private static final Logger log = LoggerFactory.getLogger(TareasRecordatorios.class);
    private final NotificacionService notificaciones;

    public TareasRecordatorios(NotificacionService notificaciones) {
        this.notificaciones = notificaciones;
    }

    /** Cada 5 minutos: programa los recordatorios de las citas confirmadas que aún no los tienen. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 45_000)
    public void programar() {
        try {
            int creados = notificaciones.programarRecordatorios();
            if (creados > 0) log.info("Se programaron {} recordatorios de cita", creados);
        } catch (RuntimeException e) {
            log.error("Falló la tarea de programar recordatorios", e);
        }
    }

    /** Cada minuto: envía los recordatorios cuya hora ya llegó. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void enviar() {
        try {
            int enviados = notificaciones.enviarPendientes();
            if (enviados > 0) log.info("Se enviaron {} recordatorios de cita", enviados);
        } catch (RuntimeException e) {
            log.error("Falló la tarea de enviar recordatorios", e);
        }
    }
}

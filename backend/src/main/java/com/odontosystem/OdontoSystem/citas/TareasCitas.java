package com.odontosystem.OdontoSystem.citas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Tareas automáticas de la agenda (System Jobs del A2). */
@Component
public class TareasCitas {

    private static final Logger log = LoggerFactory.getLogger(TareasCitas.class);

    private final CitaService citaService;

    public TareasCitas(CitaService citaService) {
        this.citaService = citaService;
    }

    /** Cada minuto: las reservas que no pagaron el depósito en 15 minutos se cancelan y el turno se libera. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void vencerReservasSinPago() {
        try {
            int vencidas = citaService.vencerReservasSinPago();
            if (vencidas > 0) {
                log.info("Se liberaron {} reservas que no se pagaron a tiempo", vencidas);
            }
        } catch (RuntimeException e) {
            log.error("Falló la tarea de vencer reservas sin pago", e);
        }
    }
}

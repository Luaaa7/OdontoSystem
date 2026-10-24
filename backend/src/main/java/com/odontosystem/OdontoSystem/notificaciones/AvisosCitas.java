package com.odontosystem.OdontoSystem.notificaciones;

import com.odontosystem.OdontoSystem.citas.CitaCambioEstado;
import com.odontosystem.OdontoSystem.citas.CitaReprogramada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Escucha los cambios de estado de las citas y manda el aviso por correo.
 * Corre después de que el cambio se guardó (si la transacción se deshace, no sale ningún correo)
 * y en otro hilo (@Async): el paciente no espera a que responda el servidor de correo.
 */
@Component
public class AvisosCitas {

    private static final Logger log = LoggerFactory.getLogger(AvisosCitas.class);
    private final NotificacionService notificaciones;

    public AvisosCitas(NotificacionService notificaciones) {
        this.notificaciones = notificaciones;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alCambiarEstado(CitaCambioEstado evento) {
        try {
            notificaciones.avisarCambioDeEstado(evento);
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar el cambio de la cita {} a {}: {}", evento.citaId(), evento.nuevo(), e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alReprogramar(CitaReprogramada evento) {
        try {
            notificaciones.avisarReprogramacion(evento);
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar la reprogramación de la cita {}: {}", evento.citaOrigenId(), e.getMessage());
        }
    }
}

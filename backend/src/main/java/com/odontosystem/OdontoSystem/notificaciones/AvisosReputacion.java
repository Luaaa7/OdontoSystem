package com.odontosystem.OdontoSystem.notificaciones;

import com.odontosystem.OdontoSystem.usuarios.StrikeSumado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Escucha los strikes que recibe un paciente y le manda el aviso.
 * Corre después del commit (si la transacción se deshace, no sale ningún correo) y en otro hilo (@Async).
 */
@Component
public class AvisosReputacion {

    private static final Logger log = LoggerFactory.getLogger(AvisosReputacion.class);
    private final NotificacionService notificaciones;

    public AvisosReputacion(NotificacionService notificaciones) {
        this.notificaciones = notificaciones;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alSumarStrike(StrikeSumado evento) {
        try {
            notificaciones.avisarStrike(evento);
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar el strike del paciente {}: {}", evento.pacienteId(), e.getMessage());
        }
    }
}
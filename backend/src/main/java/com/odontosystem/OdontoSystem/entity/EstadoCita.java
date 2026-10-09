package com.odontosystem.OdontoSystem.entity;

/**
 * Estados de una cita (tipo estado_cita de la BD v9).
 * PENDIENTE_PAGO y CONFIRMADA bloquean el horario en la agenda; PENDIENTE_CONFIRMACION no (RF11).
 */
public enum EstadoCita {
    PENDIENTE_PAGO,
    PENDIENTE_CONFIRMACION,
    CONFIRMADA,
    COMPLETADA,
    CANCELADA_CON_REEMBOLSO,
    CANCELADA_SIN_REEMBOLSO,
    INASISTENCIA_PENALIZADA,
    REPROGRAMADA
}

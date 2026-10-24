package com.odontosystem.OdontoSystem.citas;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Se publica cuando un paciente reprograma una cita (lo escucha el módulo de notificaciones).
 * inicioAnterior: la fecha y hora que tenía la cita original, para el texto del aviso.
 */
public record CitaReprogramada(UUID citaOrigenId, UUID citaNuevaId, OffsetDateTime inicioAnterior) {}

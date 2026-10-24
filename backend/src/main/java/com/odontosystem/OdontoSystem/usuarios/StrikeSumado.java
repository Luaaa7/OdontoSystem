package com.odontosystem.OdontoSystem.usuarios;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Se publica cuando un paciente recibe un strike (lo escucha el módulo de
 * notificaciones).
 * restringidoHasta solo viene con valor si este strike lo dejó suspendido.
 */
public record StrikeSumado(UUID pacienteId, String nombre, String correo, short strikes, short limite,
        OffsetDateTime restringidoHasta) {
}
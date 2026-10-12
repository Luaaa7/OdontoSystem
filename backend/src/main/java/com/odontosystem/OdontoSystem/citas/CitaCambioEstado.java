package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.entity.EstadoCita;

import java.util.UUID;

/** Se publica cada vez que una cita cambia de estado (lo escucha el módulo de notificaciones). */
public record CitaCambioEstado(UUID citaId, EstadoCita anterior, EstadoCita nuevo) {}

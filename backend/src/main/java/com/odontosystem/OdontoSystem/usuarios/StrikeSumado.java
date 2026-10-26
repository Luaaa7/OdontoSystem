package com.odontosystem.OdontoSystem.usuarios;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Se publica cuando un paciente recibe un strike (lo escucha el módulo de
 * notificaciones).
 * restringidoHasta solo viene con valor si el paciente está suspendido.
 * diasSuspension: valor de DIAS_SUSPENSION_STRIKES (para el texto del correo).
 * repetido: el paciente ya estaba en el límite (suspendido) y volvió a faltar; el contador no sube
 * y la suspensión no se alarga, pero se le avisa que la falta quedó registrada.
 */
public record StrikeSumado(UUID pacienteId, String nombre, String correo, short strikes, short limite,
        OffsetDateTime restringidoHasta, int diasSuspension, boolean repetido) {

    /** Strike normal (no repetido) con la suspensión por defecto de 30 días. */
    public StrikeSumado(UUID pacienteId, String nombre, String correo, short strikes, short limite,
            OffsetDateTime restringidoHasta) {
        this(pacienteId, nombre, correo, strikes, limite, restringidoHasta, 30, false);
    }
}

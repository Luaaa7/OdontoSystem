package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.entity.EstadoCita;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Peticiones y respuestas del módulo de citas (JSON en snake_case). */
public final class CitaDtos {

    private CitaDtos() {
    }

    public record Turno(OffsetDateTime inicio, OffsetDateTime fin) {}

    public record DisponibilidadResponse(UUID odontologoId, UUID servicioId, LocalDate fecha, String zonaHoraria,
                                         short duracionMinutos, List<Turno> turnos) {}

    /** inicio con zona horaria, tal como vino en la lista de turnos (ej. 2026-10-20T09:00:00-05:00). */
    public record ReservaRequest(@NotNull UUID odontologoId,
                                 @NotNull UUID servicioId,
                                 @NotNull OffsetDateTime inicio) {}

    public record CancelacionRequest(@Size(max = 500) String motivo) {}

    public record OdontologoResumen(UUID id, String nombre, String consultorio, String direccion, String distrito) {}

    public record PacienteResumen(UUID id, String nombre, String telefono, String correo) {}

    public record ServicioResumen(UUID id, String titulo) {}

    public record CitaResponse(UUID id,
                               EstadoCita estado,
                               OffsetDateTime inicio,
                               OffsetDateTime fin,
                               String zonaHoraria,
                               BigDecimal precio,
                               BigDecimal depositoRequerido,
                               OffsetDateTime pagarAntesDe,
                               OdontologoResumen odontologo,
                               PacienteResumen paciente,
                               ServicioResumen servicio,
                               String motivoCancelacion,
                               OffsetDateTime creadoEn) {}
}

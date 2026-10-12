package com.odontosystem.OdontoSystem.resenas;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** Peticiones y respuestas del módulo de reseñas (JSON en snake_case). */
public final class ResenaDtos {

    private ResenaDtos() {
    }

    public record ResenaRequest(@NotNull @Min(1) @Max(5) Short calificacion,
                                @Size(max = 1000) String comentario) {}

    /** paciente va abreviado ("Juan Q.") porque la lista de reseñas es pública. */
    public record ResenaResponse(Long id,
                                 Short calificacion,
                                 String comentario,
                                 String paciente,
                                 OffsetDateTime creadoEn) {}
}
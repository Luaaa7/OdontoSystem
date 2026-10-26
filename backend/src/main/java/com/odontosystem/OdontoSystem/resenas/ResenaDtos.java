package com.odontosystem.OdontoSystem.resenas;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Peticiones y respuestas de reseñas (JSON en snake_case). */
public final class ResenaDtos {

    private ResenaDtos() {
    }

    public record ResenaRequest(@NotNull(message = "La calificación es obligatoria")
                                @Min(value = 1, message = "La calificación va de 1 a 5")
                                @Max(value = 5, message = "La calificación va de 1 a 5") Short calificacion,
                                @Size(max = 1000, message = "El comentario no puede superar los 1000 caracteres")
                                String comentario) {}

    /** La reseña tal como la ve su autor. */
    public record ResenaResponse(Long id, UUID citaId, UUID odontologoId, short calificacion, String comentario,
                                 OffsetDateTime creadoEn) {}

    /** Una reseña en el perfil público: el nombre del paciente va abreviado ("Lucía R."). */
    public record ResenaPublica(Long id, String paciente, short calificacion, String comentario, OffsetDateTime creadoEn) {}

    /**
     * Resumen y página de reseñas de un odontólogo.
     * distribucion: cuántas reseñas hay de cada puntaje, de 5 a 1 (siempre las 5 claves).
     */
    public record ResenasOdontologoResponse(UUID odontologoId, BigDecimal calificacion, long totalResenas,
                                            Map<Integer, Long> distribucion, List<ResenaPublica> resenas,
                                            int pagina, int tamano, int totalPaginas) {}

    /** Una cita atendida que el paciente todavía no calificó. */
    public record CitaPorResenar(UUID citaId, UUID odontologoId, String odontologo, String servicio,
                                 OffsetDateTime atendidaEl) {}
}

package com.odontosystem.OdontoSystem.reclamos;

import com.odontosystem.OdontoSystem.entity.EstadoReclamo;
import com.odontosystem.OdontoSystem.entity.TipoReclamo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Peticiones y respuestas de reclamos (JSON en snake_case). */
public final class ReclamoDtos {

    private ReclamoDtos() {
    }

    public record ReclamoRequest(@NotNull(message = "Indica si es CONSULTA, INCIDENCIA o RECLAMO") TipoReclamo tipo,
                                 @NotBlank(message = "El asunto es obligatorio")
                                 @Size(max = 150, message = "El asunto no puede superar los 150 caracteres")
                                 String asunto,
                                 @NotBlank(message = "Describe lo que pasó")
                                 @Size(max = 4000, message = "La descripción no puede superar los 4000 caracteres")
                                 String descripcion,
                                 UUID citaId) {}

    /** El odontólogo responde; con resuelto=true el reclamo pasa a RESUELTO, si no queda EN_PROCESO. */
    public record RespuestaRequest(@NotBlank(message = "Escribe la respuesta")
                                   @Size(max = 4000, message = "La respuesta no puede superar los 4000 caracteres")
                                   String respuesta,
                                   boolean resuelto) {}

    /**
     * @param codigo         número que se le muestra al usuario para hacer seguimiento (REC-XXXXXXXX)
     * @param atiende        ODONTOLOGO (reclamo de un paciente sobre una cita) o SOPORTE (equipo de OdontoSystem)
     * @param responderAntes fecha límite de respuesta: 15 días hábiles desde el registro
     */
    public record ReclamoResponse(UUID id, String codigo, TipoReclamo tipo, EstadoReclamo estado, String asunto,
                                  String descripcion, String reclamante, UUID citaId, String odontologo,
                                  String servicio, OffsetDateTime fechaCita, String atiende, String respuesta,
                                  OffsetDateTime creadoEn, OffsetDateTime actualizadoEn, OffsetDateTime resueltoEn,
                                  LocalDate responderAntes) {}
}

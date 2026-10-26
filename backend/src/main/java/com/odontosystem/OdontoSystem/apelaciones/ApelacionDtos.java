package com.odontosystem.OdontoSystem.apelaciones;

import com.odontosystem.OdontoSystem.entity.EstadoApelacion;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Peticiones y respuestas de apelaciones (JSON en snake_case). */
public final class ApelacionDtos {

    private ApelacionDtos() {
    }

    /** Decisión del odontólogo: aprobar (la inasistencia estaba justificada) o rechazar, con una nota opcional. */
    public record ResolucionRequest(@NotNull(message = "Indica si apruebas o rechazas la apelación") Boolean aprobar,
                                    @Size(max = 500, message = "La nota no puede superar los 500 caracteres") String nota) {}

    /** El certificado es privado: se entrega un enlace firmado que vence a los 10 minutos (null si no se pudo generar). */
    public record ApelacionResponse(UUID id, UUID citaId, EstadoApelacion estado, String paciente, String servicio,
                                    OffsetDateTime fechaCita, String certificadoUrl, OffsetDateTime certificadoExpiraEn,
                                    String notaResolucion, OffsetDateTime creadoEn, OffsetDateTime resueltoEn,
                                    Short strikesDelPaciente) {}
}

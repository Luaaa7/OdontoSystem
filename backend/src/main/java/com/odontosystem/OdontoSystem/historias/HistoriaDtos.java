package com.odontosystem.OdontoSystem.historias;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Peticiones y respuestas de historias clínicas (JSON en snake_case). Viajan descifradas solo por HTTPS. */
public final class HistoriaDtos {

    private HistoriaDtos() {
    }

    public record HistoriaRequest(@NotBlank(message = "El diagnóstico es obligatorio")
                                  @Size(max = 4000, message = "El diagnóstico no puede superar los 4000 caracteres")
                                  String diagnostico,
                                  @NotBlank(message = "El tratamiento es obligatorio")
                                  @Size(max = 4000, message = "El tratamiento no puede superar los 4000 caracteres")
                                  String tratamiento,
                                  @Size(max = 4000, message = "Las notas no pueden superar los 4000 caracteres")
                                  String notas) {}

    public record HistoriaResponse(Long id, UUID citaId, UUID pacienteId, String odontologo, String servicio,
                                   OffsetDateTime fechaCita, String diagnostico, String tratamiento, String notas,
                                   OffsetDateTime creadoEn) {}
}

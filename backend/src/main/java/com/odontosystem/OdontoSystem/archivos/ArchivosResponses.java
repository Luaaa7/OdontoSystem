package com.odontosystem.OdontoSystem.archivos;

import com.odontosystem.OdontoSystem.entity.TipoDocumentoOdontologo;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Respuestas JSON de los endpoints de archivos (se serializan en snake_case). */
public final class ArchivosResponses {

    private ArchivosResponses() {
    }

    public record FotoPerfilResponse(String fotoPerfilUrl) {}

    /** url_temporal vence en url_expira_en: el frontend debe pedir la lista de nuevo para renovarla. */
    public record DocumentoResponse(UUID id,
                                    TipoDocumentoOdontologo tipo,
                                    OffsetDateTime subidoEn,
                                    String urlTemporal,
                                    OffsetDateTime urlExpiraEn) {}

    public record FotoConsultorioResponse(UUID id, String url, String descripcion, short orden) {}
}

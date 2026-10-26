package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Historial de documentos del odontólogo (tabla documentos_odontologo).
 * Solo hay un documento activo por tipo: al subir uno nuevo, el anterior queda con reemplazado_en.
 * El archivo es privado en Cloudinary; url_archivo guarda la URL interna y se entrega un enlace temporal.
 */
@Entity
@Table(name = "documentos_odontologo")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentoOdontologo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "odontologo_id", nullable = false)
    private UUID odontologoId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "tipo_documento", nullable = false)
    private TipoDocumentoOdontologo tipoDocumento;

    @Column(name = "url_archivo", nullable = false, columnDefinition = "TEXT")
    private String urlArchivo;

    @Column(name = "subido_en", nullable = false)
    private OffsetDateTime subidoEn;

    @Column(name = "reemplazado_en")
    private OffsetDateTime reemplazadoEn;

    @PrePersist
    protected void alPersistir() {
        if (subidoEn == null) subidoEn = OffsetDateTime.now();
    }
}

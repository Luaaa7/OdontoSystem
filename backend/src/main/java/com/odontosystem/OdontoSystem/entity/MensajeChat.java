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

/** Un mensaje dentro de una conversación del chatbot (tabla mensajes_chat). */
@Entity
@Table(name = "mensajes_chat")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MensajeChat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sesion_id", nullable = false)
    private SesionChat sesion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "tipo_emisor", nullable = false)
    private TipoEmisorMensaje tipoEmisor;

    @Column(name = "texto_mensaje", nullable = false, columnDefinition = "TEXT")
    private String textoMensaje;

    // Nombre del enum Intencion (ej. "BUSCAR_ODONTOLOGO"). Sirve para métricas del chatbot.
    @Column(name = "intencion_detectada", length = 100)
    private String intencionDetectada;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @PrePersist
    protected void alPersistir() {
        if (creadoEn == null) creadoEn = OffsetDateTime.now();
    }
}

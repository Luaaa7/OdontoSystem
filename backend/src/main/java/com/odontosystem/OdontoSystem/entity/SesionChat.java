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

/** Una conversación entre un usuario y el chatbot (tabla sesiones_chat). */
@Entity
@Table(name = "sesiones_chat")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SesionChat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "canal", nullable = false)
    private CanalChat canal;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado", nullable = false)
    @Builder.Default
    private EstadoChat estado = EstadoChat.ACTIVA;

    /** Memoria de contexto del chatbot (ver chatbot.ContextoChat). JSON; "{}" = sin flujo en curso. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contexto", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private String contexto = "{}";

    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    // La BD también lo actualiza con el trigger trg_sesiones_chat_actualizado_en.
    @Column(name = "actualizado_en", nullable = false)
    private OffsetDateTime actualizadoEn;

    @PrePersist
    protected void alPersistir() {
        OffsetDateTime ahora = OffsetDateTime.now();
        if (creadoEn == null) creadoEn = ahora;
        if (actualizadoEn == null) actualizadoEn = ahora;
    }
}

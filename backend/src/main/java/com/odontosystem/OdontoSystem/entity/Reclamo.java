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
 * Consulta, incidencia o reclamo de un usuario (tabla reclamos), con cita asociada opcional.
 * La BD exige que resuelto_en solo tenga valor si el estado es RESUELTO o CERRADO,
 * y su trigger mantiene actualizado_en.
 */
@Entity
@Table(name = "reclamos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reclamo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false, updatable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cita_id", updatable = false)
    private Cita cita;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "tipo", nullable = false, updatable = false)
    private TipoReclamo tipo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado", nullable = false)
    @Builder.Default
    private EstadoReclamo estado = EstadoReclamo.ABIERTO;

    @Column(name = "asunto", nullable = false, length = 150, updatable = false)
    private String asunto;

    @Column(name = "descripcion", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String descripcion;

    @Column(name = "respuesta", columnDefinition = "TEXT")
    private String respuesta;

    @Column(name = "resuelto_en")
    private OffsetDateTime resueltoEn;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private OffsetDateTime actualizadoEn;

    @PrePersist
    protected void alPersistir() {
        OffsetDateTime ahora = OffsetDateTime.now();
        if (creadoEn == null) creadoEn = ahora;
        if (actualizadoEn == null) actualizadoEn = ahora;
    }

    @PreUpdate
    protected void alActualizar() {
        actualizadoEn = OffsetDateTime.now();
    }
}

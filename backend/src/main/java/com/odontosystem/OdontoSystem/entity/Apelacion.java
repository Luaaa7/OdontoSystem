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
 * Apelación del paciente sobre una inasistencia penalizada, con un certificado adjunto (tabla apelaciones).
 * La resuelve el odontólogo de esa cita (arquitectura sin administrador). Una por cita.
 */
@Entity
@Table(name = "apelaciones")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Apelacion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cita_id", nullable = false, updatable = false)
    private Cita cita;

    /** URL privada en Cloudinary: solo se abre con un enlace temporal firmado. */
    @Column(name = "url_certificado", nullable = false, columnDefinition = "TEXT")
    private String urlCertificado;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado", nullable = false)
    @Builder.Default
    private EstadoApelacion estado = EstadoApelacion.PENDIENTE;

    @Column(name = "resuelto_por_odontologo_id")
    private UUID resueltoPorOdontologoId;

    @Column(name = "nota_resolucion", columnDefinition = "TEXT")
    private String notaResolucion;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "resuelto_en")
    private OffsetDateTime resueltoEn;

    @PrePersist
    protected void alPersistir() {
        if (creadoEn == null) creadoEn = OffsetDateTime.now();
    }
}

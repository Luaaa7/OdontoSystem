package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Reseña de un paciente sobre una cita atendida (tabla resenas).
 * La BD garantiza: una reseña por cita, cita COMPLETADA, paciente y odontólogo de esa cita,
 * calificación de 1 a 5, y recalcula calificacion_promedio / total_resenas del odontólogo con triggers.
 */
@Entity
@Table(name = "resenas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Resena {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cita_id", nullable = false, unique = true, updatable = false)
    private Cita cita;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "odontologo_id", nullable = false, updatable = false)
    private PerfilOdontologo odontologo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paciente_id", nullable = false, updatable = false)
    private Usuario paciente;

    @Column(name = "calificacion", nullable = false)
    private Short calificacion;

    @Column(name = "comentario", columnDefinition = "TEXT")
    private String comentario;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @PrePersist
    protected void alPersistir() {
        if (creadoEn == null) creadoEn = OffsetDateTime.now();
    }
}
package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Reseña de una cita atendida (tabla resenas). Una por cita.
 * La BD exige que la cita esté COMPLETADA y que paciente y odontólogo sean los de la cita;
 * al guardar, sus triggers recalculan calificacion_promedio y total_resenas del odontólogo.
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cita_id", nullable = false, updatable = false)
    private Cita cita;

    @Column(name = "odontologo_id", nullable = false, updatable = false)
    private UUID odontologoId;

    @ManyToOne(fetch = FetchType.LAZY)
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

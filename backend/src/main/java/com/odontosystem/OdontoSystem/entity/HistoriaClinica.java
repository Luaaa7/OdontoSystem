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
 * Historia clínica de una cita atendida (tabla historias_clinicas). Ley N.° 29733: diagnóstico, tratamiento y
 * notas se guardan CIFRADOS (AES-256-GCM, ver historias.CifradoClinico); en la BD solo hay bytes ilegibles.
 */
@Entity
@Table(name = "historias_clinicas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HistoriaClinica {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cita_id", nullable = false, updatable = false)
    private Cita cita;

    @Column(name = "paciente_id", nullable = false, updatable = false)
    private UUID pacienteId;

    @Column(name = "creado_por_odontologo_id", updatable = false)
    private UUID creadoPorOdontologoId;

    @Column(name = "diagnostico_cifrado", nullable = false, columnDefinition = "bytea")
    private byte[] diagnosticoCifrado;

    @Column(name = "tratamiento_cifrado", nullable = false, columnDefinition = "bytea")
    private byte[] tratamientoCifrado;

    @Column(name = "notas_clinicas_cifradas", columnDefinition = "bytea")
    private byte[] notasClinicasCifradas;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @PrePersist
    protected void alPersistir() {
        if (creadoEn == null) creadoEn = OffsetDateTime.now();
    }
}

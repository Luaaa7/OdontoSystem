package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Reporte del paciente: "el odontólogo no llegó" (tabla reportes_ausencia). */
@Entity
@Table(name = "reportes_ausencia")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReporteAusencia {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cita_id", nullable = false, updatable = false)
    private Cita cita;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    @Builder.Default
    private EstadoReporteAusencia estado = EstadoReporteAusencia.PENDIENTE;

    /** Distancia del paciente al consultorio al reportar (null si no compartió su ubicación). */
    @Column(name = "distancia_metros")
    private Integer distanciaMetros;

    @Column(name = "respuesta_odontologo", columnDefinition = "TEXT")
    private String respuestaOdontologo;

    @Column(name = "respondido_en")
    private OffsetDateTime respondidoEn;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "resuelto_en")
    private OffsetDateTime resueltoEn;

    @PrePersist
    protected void alPersistir() {
        if (creadoEn == null) creadoEn = OffsetDateTime.now();
    }
}

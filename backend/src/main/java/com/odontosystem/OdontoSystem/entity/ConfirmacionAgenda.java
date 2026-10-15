package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Respuesta diaria del odontólogo a "¿atiendes hoy?" (tabla confirmaciones_agenda). */
@Entity
@Table(name = "confirmaciones_agenda")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmacionAgenda {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "odontologo_id", nullable = false, updatable = false)
    private UUID odontologoId;

    @Column(name = "fecha", nullable = false, updatable = false)
    private LocalDate fecha;

    @Column(name = "atiende", nullable = false)
    private boolean atiende;

    @Column(name = "motivo", length = 255)
    private String motivo;

    @Column(name = "respondido_en", nullable = false)
    private OffsetDateTime respondidoEn;

    @PrePersist
    protected void alPersistir() {
        if (respondidoEn == null) respondidoEn = OffsetDateTime.now();
    }
}

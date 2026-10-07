package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Galería pública del consultorio (tabla fotos_consultorio). */
@Entity
@Table(name = "fotos_consultorio")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FotoConsultorio {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "odontologo_id", nullable = false)
    private UUID odontologoId;

    @Column(name = "url_foto", nullable = false, columnDefinition = "TEXT")
    private String urlFoto;

    @Column(name = "descripcion", length = 255)
    private String descripcion;

    @Column(name = "orden", nullable = false)
    private Short orden;

    @Column(name = "creado_en", nullable = false)
    private OffsetDateTime creadoEn;

    @PrePersist
    protected void alPersistir() {
        if (creadoEn == null) creadoEn = OffsetDateTime.now();
    }
}

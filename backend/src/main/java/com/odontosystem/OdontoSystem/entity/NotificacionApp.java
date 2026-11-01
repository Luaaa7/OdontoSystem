package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Un aviso en la bandeja de la app (tabla notificaciones_app, V11). leidaEn null = no leída. */
@Entity
@Table(name = "notificaciones_app")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificacionApp {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    /** RECORDATORIO_CITA, CONFIRMACION_PAGO, AVISO_CANCELACION, AVISO_REPROGRAMACION, ALERTA_STRIKE, RECLAMO… */
    @Column(name = "tipo", nullable = false, length = 40)
    private String tipo;

    @Column(name = "titulo", nullable = false, length = 150)
    private String titulo;

    @Column(name = "mensaje", nullable = false, length = 300)
    private String mensaje;

    @Column(name = "cita_id")
    private UUID citaId;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "leida_en")
    private OffsetDateTime leidaEn;

    @PrePersist
    protected void alPersistir() {
        if (creadoEn == null) creadoEn = OffsetDateTime.now();
    }
}

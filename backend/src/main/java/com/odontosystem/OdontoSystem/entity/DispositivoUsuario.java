package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Celular o navegador de un usuario que recibe notificaciones push (tabla dispositivos_usuario). */
@Entity
@Table(name = "dispositivos_usuario")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispositivoUsuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    /** Token que entrega Firebase Cloud Messaging al celular o navegador. */
    @Column(name = "token_fcm", nullable = false, columnDefinition = "TEXT")
    private String tokenFcm;

    @Column(name = "sistema_operativo", length = 20)
    private String sistemaOperativo;

    @Column(name = "ultima_actividad_en", nullable = false)
    private OffsetDateTime ultimaActividadEn;
}

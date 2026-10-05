package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "usuarios")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "numero_documento", nullable = false, unique = true, length = 20)
    private String numeroDocumento;

    @Column(name = "tipo_documento", nullable = false, length = 5)
    @Builder.Default
    private String tipoDocumento = "DNI";

    @Column(name = "nombre_completo", nullable = false, length = 150)
    private String nombreCompleto;

    @Column(name = "telefono", nullable = false, unique = true, length = 20)
    private String telefono;

    @Column(name = "correo", nullable = false, unique = true, length = 150)
    private String correo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "rol", nullable = false)
    private RolUsuario rol;

    // Hash BCrypt (60 car.). NULL si la cuenta solo entra por Google (RF16).
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    // Claim "sub" del token OIDC de Google. NULL si la cuenta entra por password.
    @Column(name = "google_oauth_sub", unique = true, length = 255)
    private String googleOauthSub;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "metodo_registro", nullable = false)
    @Builder.Default
    private MetodoAutenticacion metodoRegistro = MetodoAutenticacion.PASSWORD;

    @Column(name = "foto_perfil_url", columnDefinition = "TEXT")
    private String fotoPerfilUrl;

    @Column(name = "biometria_habilitada", nullable = false)
    @Builder.Default
    private Boolean biometriaHabilitada = false;

    @Column(name = "telefono_verificado_en")
    private OffsetDateTime telefonoVerificadoEn;

    @Column(name = "email_verificado_en")
    private OffsetDateTime emailVerificadoEn;

    @Column(name = "ultimo_acceso_en")
    private OffsetDateTime ultimoAccesoEn;

    @Column(name = "esta_activo", nullable = false)
    @Builder.Default
    private Boolean estaActivo = true;

    @CreationTimestamp
    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @UpdateTimestamp
    @Column(name = "actualizado_en", nullable = false)
    private OffsetDateTime actualizadoEn;
}
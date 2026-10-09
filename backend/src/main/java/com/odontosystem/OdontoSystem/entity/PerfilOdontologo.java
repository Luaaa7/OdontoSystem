package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "perfiles_odontologo")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PerfilOdontologo {

    @Id
    @Column(name = "usuario_id")
    private UUID usuarioId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "numero_colegiatura", nullable = false, unique = true, length = 30)
    private String numeroColegiatura;

    @Column(name = "colegiatura_verificada", nullable = false)
    @Builder.Default
    private boolean colegiaturaVerificada = false;

    @Column(name = "colegiatura_verificada_en")
    private OffsetDateTime colegiaturaVerificadaEn;

    @Column(name = "ruc", length = 11)
    private String ruc;

    @Column(name = "cci", length = 20)
    private String cci;

    @Column(name = "nombre_consultorio", length = 150)
    private String nombreConsultorio;

    @Column(name = "direccion_consultorio", length = 255)
    private String direccionConsultorio;

    @Column(name = "distrito_consultorio", length = 100)
    private String distritoConsultorio;

    @Column(name = "zona_horaria", nullable = false, length = 50)
    @Builder.Default
    private String zonaHoraria = "America/Lima";

    @Column(name = "latitud_consultorio", precision = 9, scale = 6)
    private BigDecimal latitudConsultorio;

    @Column(name = "longitud_consultorio", precision = 9, scale = 6)
    private BigDecimal longitudConsultorio;

    // La columna geoespacial la recalcula un trigger de BD a partir de lat/long; la app nunca la escribe.
    // La mapeamos más adelante (con hibernate-spatial) cuando implementemos la búsqueda por cercanía.

    @Column(name = "calificacion_promedio", nullable = false, precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal calificacionPromedio = BigDecimal.ZERO;

    @Column(name = "total_resenas", nullable = false)
    @Builder.Default
    private Integer totalResenas = 0;

    @Column(name = "requiere_confirmacion_manual", nullable = false)
    @Builder.Default
    private boolean requiereConfirmacionManual = false;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "frecuencia_disponibilidad", nullable = false)
    @Builder.Default
    private FrecuenciaDisponibilidad frecuenciaDisponibilidad = FrecuenciaDisponibilidad.SEMANAL;

    @Column(name = "disponibilidad_actualizada_en")
    private OffsetDateTime disponibilidadActualizadaEn;

    @Column(name = "desactivado_en")
    private OffsetDateTime desactivadoEn;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private OffsetDateTime actualizadoEn;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado_verificacion", nullable = false)
    @Builder.Default
    private EstadoVerificacionOdontologo estadoVerificacion = EstadoVerificacionOdontologo.PENDIENTE;

    @Column(name = "verificado_en")
    private OffsetDateTime verificadoEn;

    @Column(name = "nota_observacion", columnDefinition = "TEXT")
    private String notaObservacion;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "especialidades_odontologo",
            joinColumns = @JoinColumn(name = "odontologo_id"),
            inverseJoinColumns = @JoinColumn(name = "especialidad_id"))
    @Builder.Default
    private Set<Especialidad> especialidades = new HashSet<>();

    @PrePersist
    protected void alPersistir() {
        OffsetDateTime ahora = OffsetDateTime.now();
        if (creadoEn == null) creadoEn = ahora;
        if (actualizadoEn == null) actualizadoEn = ahora;
    }
}
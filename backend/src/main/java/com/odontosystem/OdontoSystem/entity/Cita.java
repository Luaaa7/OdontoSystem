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
import java.util.UUID;

/**
 * Cita (tabla citas). Varias columnas las fija la BD con triggers al insertar:
 * precio_servicio, monto_deposito_requerido, duracion_minutos y zona_horaria (copia del servicio),
 * y tolerancia_vence_en. Por eso el servicio de citas recarga la entidad después de guardarla.
 * La BD también impide que dos citas activas del mismo odontólogo se crucen (ex_citas_sin_solape)
 * y registra cada cambio de estado en bitacora_auditoria_citas.
 */
@Entity
@Table(name = "citas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cita {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paciente_id", nullable = false, updatable = false)
    private Usuario paciente;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "odontologo_id", nullable = false, updatable = false)
    private PerfilOdontologo odontologo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "servicio_id", nullable = false, updatable = false)
    private Servicio servicio;

    @Column(name = "cita_origen_id")
    private UUID citaOrigenId;

    @Column(name = "inicio_programado", nullable = false)
    private OffsetDateTime inicioProgramado;

    @Column(name = "fin_programado", nullable = false)
    private OffsetDateTime finProgramado;

    @Column(name = "zona_horaria", nullable = false, length = 50)
    private String zonaHoraria;

    @Column(name = "precio_servicio", nullable = false, precision = 10, scale = 2)
    private BigDecimal precioServicio;

    @Column(name = "monto_deposito_requerido", nullable = false, precision = 10, scale = 2)
    private BigDecimal montoDepositoRequerido;

    @Column(name = "duracion_minutos", nullable = false)
    private Short duracionMinutos;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado", nullable = false)
    @Builder.Default
    private EstadoCita estado = EstadoCita.PENDIENTE_PAGO;

    @Column(name = "referencia_bloqueo", length = 100)
    private String referenciaBloqueo;

    @Column(name = "registrado_en")
    private OffsetDateTime registradoEn;

    @Column(name = "tolerancia_vence_en")
    private OffsetDateTime toleranciaVenceEn;

    @Column(name = "completado_en")
    private OffsetDateTime completadoEn;

    @Column(name = "cancelado_en")
    private OffsetDateTime canceladoEn;

    @Column(name = "motivo_cancelacion", columnDefinition = "TEXT")
    private String motivoCancelacion;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private OffsetDateTime actualizadoEn;

    @PrePersist
    protected void alPersistir() {
        OffsetDateTime ahora = OffsetDateTime.now();
        if (creadoEn == null) creadoEn = ahora;
        if (actualizadoEn == null) actualizadoEn = ahora;
    }
}

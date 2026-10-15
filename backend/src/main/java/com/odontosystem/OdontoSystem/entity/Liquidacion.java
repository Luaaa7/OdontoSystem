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
 * Pago al odontólogo del depósito de una cita (tabla liquidaciones). Mientras está PENDIENTE el dinero sigue en
 * custodia de la plataforma; LIQUIDADA = transferido (simulado: referencia "sim_trf_…"); CANCELADA = el depósito
 * se le devolvió al paciente.
 */
@Entity
@Table(name = "liquidaciones")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Liquidacion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "odontologo_id", nullable = false, updatable = false)
    private PerfilOdontologo odontologo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cita_id")
    private Cita cita;

    @Column(name = "monto", nullable = false, precision = 10, scale = 2)
    private BigDecimal monto;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado", nullable = false)
    @Builder.Default
    private EstadoLiquidacion estado = EstadoLiquidacion.PENDIENTE;

    /** N.º de operación de la transferencia (UNIQUE en la BD). */
    @Column(name = "referencia_pago", length = 80)
    private String referenciaPago;

    @Column(name = "motivo_fallo", columnDefinition = "TEXT")
    private String motivoFallo;

    @Column(name = "liquidado_en")
    private OffsetDateTime liquidadoEn;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @PrePersist
    protected void alPersistir() {
        if (creadoEn == null) creadoEn = OffsetDateTime.now();
    }
}

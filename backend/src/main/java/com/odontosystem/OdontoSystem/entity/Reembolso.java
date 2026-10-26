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
 * RF15: devolución (total o parcial) de un pago (tabla reembolsos).
 * La BD exige que el pago esté VERIFICADO, que la pasarela coincida y que la suma no supere el pago;
 * cuando el total queda REEMBOLSADO, su trigger pasa el pago a REEMBOLSADO.
 */
@Entity
@Table(name = "reembolsos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reembolso {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pago_id", nullable = false, updatable = false)
    private Pago pago;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "pasarela_pago", nullable = false)
    private PasarelaPago pasarelaPago;

    /** Evita reembolsar dos veces lo mismo (por ejemplo, "cancelacion:<id de la cita>"). */
    @Column(name = "clave_idempotencia", nullable = false, length = 100)
    private String claveIdempotencia;

    @Column(name = "id_externo", length = 150)
    private String idExterno;

    @Column(name = "monto", nullable = false, precision = 10, scale = 2)
    private BigDecimal monto;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado", nullable = false)
    @Builder.Default
    private EstadoReembolso estado = EstadoReembolso.SOLICITADO;

    @Column(name = "motivo", columnDefinition = "TEXT")
    private String motivo;

    @Column(name = "solicitado_en", nullable = false, updatable = false)
    private OffsetDateTime solicitadoEn;

    @Column(name = "procesado_en")
    private OffsetDateTime procesadoEn;

    @Column(name = "mensaje_error", columnDefinition = "TEXT")
    private String mensajeError;

    @PrePersist
    protected void alPersistir() {
        if (solicitadoEn == null) solicitadoEn = OffsetDateTime.now();
    }
}

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

/** Cada intento de cobro ante la pasarela (tabla intentos_pago); la clave de idempotencia evita cobrar dos veces. */
@Entity
@Table(name = "intentos_pago")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentoPago {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pago_id", nullable = false, updatable = false)
    private Pago pago;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "pasarela_pago", nullable = false)
    private PasarelaPago pasarelaPago;

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
    private EstadoIntentoPago estado = EstadoIntentoPago.CREADO;

    @Column(name = "mensaje_error", columnDefinition = "TEXT")
    private String mensajeError;

    @Column(name = "solicitado_en", nullable = false, updatable = false)
    private OffsetDateTime solicitadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private OffsetDateTime actualizadoEn;

    @Column(name = "confirmado_en")
    private OffsetDateTime confirmadoEn;

    @PrePersist
    protected void alPersistir() {
        OffsetDateTime ahora = OffsetDateTime.now();
        if (solicitadoEn == null) solicitadoEn = ahora;
        if (actualizadoEn == null) actualizadoEn = ahora;
    }
}

package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Aviso de la pasarela de que un cobro se aprobó (tabla eventos_pago_webhook).
 * payload_hash lo calcula la BD con un trigger, por eso no se mapea.
 */
@Entity
@Table(name = "eventos_pago_webhook")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventoPagoWebhook {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pago_id")
    private Pago pago;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "intento_pago_id")
    private IntentoPago intentoPago;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "pasarela_pago", nullable = false)
    private PasarelaPago pasarelaPago;

    @Column(name = "evento_externo_id", nullable = false, length = 150)
    private String eventoExternoId;

    @Column(name = "tipo_evento", nullable = false, length = 100)
    private String tipoEvento;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "firma_recibida", columnDefinition = "TEXT")
    private String firmaRecibida;

    @Column(name = "firma_verificada", nullable = false)
    private boolean firmaVerificada;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado", nullable = false)
    @Builder.Default
    private EstadoWebhookPago estado = EstadoWebhookPago.RECIBIDO;

    @Column(name = "recibido_en", nullable = false, updatable = false)
    private OffsetDateTime recibidoEn;

    @Column(name = "procesado_en")
    private OffsetDateTime procesadoEn;

    @Column(name = "mensaje_error", columnDefinition = "TEXT")
    private String mensajeError;

    @PrePersist
    protected void alPersistir() {
        if (recibidoEn == null) recibidoEn = OffsetDateTime.now();
    }
}

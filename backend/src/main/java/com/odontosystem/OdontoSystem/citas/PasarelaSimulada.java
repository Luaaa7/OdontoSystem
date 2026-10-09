package com.odontosystem.OdontoSystem.citas;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Pasarela de pago SIMULADA para la demo, mientras no estén las llaves de Culqi.
 * Hace lo mismo que hará Culqi: aprueba el cobro y emite un aviso (webhook) firmado con HMAC-SHA256.
 * El backend verifica esa firma igual que verificará la de Culqi, así que el resto del flujo
 * (aviso registrado → pago VERIFICADO → cita CONFIRMADA) es el definitivo.
 * La llave se genera al arrancar y nunca sale del servidor: nadie de afuera puede fabricar un aviso válido.
 */
@Component
public class PasarelaSimulada {

    public record Cobro(String idExterno, String eventoId, String payload, String firma) {}

    private final byte[] llave;

    public PasarelaSimulada() {
        this.llave = new byte[32];
        new SecureRandom().nextBytes(llave);
    }

    PasarelaSimulada(byte[] llave) {
        this.llave = llave;
    }

    /** Aprueba el cobro y devuelve el aviso firmado que mandaría la pasarela. */
    public Cobro cobrar(UUID intentoId, BigDecimal monto, String metodo) {
        String idExterno = "sim_chr_" + UUID.randomUUID().toString().replace("-", "");
        String eventoId = "sim_evt_" + UUID.randomUUID().toString().replace("-", "");
        String payload = "{\"id\":\"" + eventoId + "\",\"type\":\"charge.succeeded\",\"simulado\":true,"
                + "\"data\":{\"id\":\"" + idExterno + "\",\"intento_id\":\"" + intentoId + "\","
                + "\"amount\":" + monto.toPlainString() + ",\"currency\":\"PEN\",\"method\":\"" + metodo + "\"}}";
        return new Cobro(idExterno, eventoId, payload, firmar(payload));
    }

    /** Comparación en tiempo constante, para no filtrar información por el tiempo de respuesta. */
    public boolean firmaValida(String payload, String firma) {
        if (payload == null || firma == null) return false;
        return MessageDigest.isEqual(firmar(payload).getBytes(StandardCharsets.UTF_8),
                firma.getBytes(StandardCharsets.UTF_8));
    }

    String firmar(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(llave, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el aviso de pago", e);
        }
    }
}

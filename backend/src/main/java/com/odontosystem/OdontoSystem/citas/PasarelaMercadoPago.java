package com.odontosystem.OdontoSystem.citas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cliente de la API de Orders de Mercado Pago (Checkout API), en modo prueba o real según las credenciales.
 * <ul>
 *   <li>La tarjeta o el Yape los tokeniza el frontend con MercadoPago.js / Bricks y la Public Key: el backend nunca
 *       ve el número de tarjeta, solo recibe el token y el medio ({@code visa}, {@code master}, {@code yape}…).</li>
 *   <li>El backend crea la orden con el Access Token ({@code POST /v1/orders}, procesamiento automático) y, para un
 *       aviso (webhook), vuelve a consultar la orden ({@code GET /v1/orders/{id}}): solo se cree lo que responde la
 *       API con nuestra credencial, nunca lo que dice el cuerpo del aviso.</li>
 *   <li>Los montos viajan como texto con dos decimales ({@code "16.00"}); aquí se manejan en céntimos.</li>
 * </ul>
 * Credenciales: MERCADOPAGO_PUBLIC_KEY y MERCADOPAGO_ACCESS_TOKEN (Railway / .env). Sin ellas queda "no configurada".
 */
@Component
public class PasarelaMercadoPago {

    private static final Logger log = LoggerFactory.getLogger(PasarelaMercadoPago.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String MENSAJE_RECHAZO = "El pago fue rechazado. Revisa los datos o usa otro medio de pago.";

    /**
     * Una orden tal como la devolvió la API. {@code id} es null si Mercado Pago la rechazó sin crearla.
     * estado: processed (cobrada), failed / canceled (rechazada) o processing / action_required (en curso).
     */
    public record Orden(String id, String estado, String detalle, long montoPagadoCentimos, String referenciaExterna,
                        String mensajeUsuario, String json) {

        public boolean aprobada() {
            return "processed".equals(estado) && (detalle == null || "accredited".equals(detalle));
        }

        public boolean rechazada() {
            return "failed".equals(estado) || "canceled".equals(estado) || "expired".equals(detalle);
        }
    }

    /** Mercado Pago no respondió (red, 5xx o credencial inválida): no se sabe si cobró. */
    public static class MercadoPagoNoDisponible extends RuntimeException {
        public MercadoPagoNoDisponible(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    private final RestClient api;
    private final String llavePublica;
    private final boolean configurada;
    private final boolean prueba;

    @Autowired
    public PasarelaMercadoPago(@Value("${app.pagos.mercadopago.llave-publica:}") String llavePublica,
                               @Value("${app.pagos.mercadopago.access-token:}") String accessToken,
                               @Value("${app.pagos.mercadopago.url:https://api.mercadopago.com}") String url,
                               @Value("${app.pagos.mercadopago.prueba:true}") boolean prueba) {
        this(llavePublica, accessToken, RestClient.builder().baseUrl(url).requestFactory(conTiempos()), prueba);
    }

    PasarelaMercadoPago(String llavePublica, String accessToken, RestClient.Builder builder, boolean prueba) {
        this.llavePublica = llavePublica == null ? "" : llavePublica.strip();
        String token = accessToken == null ? "" : accessToken.strip();
        this.configurada = !this.llavePublica.isEmpty() && !token.isEmpty();
        this.prueba = prueba;
        this.api = builder.defaultHeader("Authorization", "Bearer " + token).build();
    }

    /** Sin credenciales: para pruebas y para quien no usa Mercado Pago. */
    static PasarelaMercadoPago sinConfigurar() {
        return new PasarelaMercadoPago("", "", RestClient.builder(), true);
    }

    private static SimpleClientHttpRequestFactory conTiempos() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(25));
        return f;
    }

    public boolean configurada() {
        return configurada;
    }

    /** La Public Key la necesita el frontend para tokenizar (MercadoPago.js / Bricks); no es secreta. */
    public String llavePublica() {
        return llavePublica;
    }

    /** true con credenciales de prueba (MERCADOPAGO_PRUEBA): ningún cobro es real. */
    public boolean modoPrueba() {
        return prueba;
    }

    /** Tipo de medio que pide la API: Yape y las tarjetas de débito ("debvisa", "debmaster") son debit_card. */
    static String tipoDeMedio(String medioId) {
        String id = medioId == null ? "" : medioId.strip().toLowerCase();
        return id.equals("yape") || id.startsWith("deb") ? "debit_card" : "credit_card";
    }

    /**
     * Crea la orden y la procesa en el acto. Si Mercado Pago rechaza el pago devuelve una {@link Orden} rechazada con
     * el mensaje para el paciente; si no responde lanza {@link MercadoPagoNoDisponible}.
     * La clave de idempotencia evita un cobro doble si se reintenta la misma llamada.
     */
    public Orden cobrar(String token, String medioId, long montoCentimos, String correo, String referenciaExterna,
                        String claveIdempotencia) {
        String monto = BigDecimal.valueOf(montoCentimos, 2).toPlainString();
        Map<String, Object> medio = new LinkedHashMap<>();
        medio.put("id", medioId.strip().toLowerCase());
        medio.put("type", tipoDeMedio(medioId));
        medio.put("token", token);
        if (!"yape".equals(medio.get("id"))) {
            medio.put("installments", 1);
        }
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("type", "online");
        cuerpo.put("processing_mode", "automatic");
        cuerpo.put("total_amount", monto);
        cuerpo.put("external_reference", referenciaExterna);
        cuerpo.put("payer", Map.of("email", correo));
        cuerpo.put("transactions", Map.of("payments", List.of(Map.of("amount", monto, "payment_method", medio))));
        try {
            String respuesta = api.post().uri("/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Key", claveIdempotencia)
                    .body(aJson(cuerpo))
                    .retrieve()
                    .body(String.class);
            return leerOrden(respuesta);
        } catch (RestClientResponseException e) {
            int codigo = e.getStatusCode().value();
            if (e.getStatusCode().is4xxClientError() && codigo != 401 && codigo != 403 && codigo != 409 && codigo != 429) {
                return leerRechazo(e.getResponseBodyAsString());
            }
            throw new MercadoPagoNoDisponible("Mercado Pago respondió " + codigo, e);
        } catch (ResourceAccessException e) {
            throw new MercadoPagoNoDisponible("No se pudo conectar con Mercado Pago", e);
        }
    }

    /** Consulta una orden por su id. Vacío si Mercado Pago no la conoce. */
    public Optional<Orden> consultarOrden(String ordenId) {
        try {
            String respuesta = api.get().uri("/v1/orders/{id}", ordenId).retrieve().body(String.class);
            return Optional.of(leerOrden(respuesta));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 400) {
                return Optional.empty();
            }
            throw new MercadoPagoNoDisponible("Mercado Pago respondió " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new MercadoPagoNoDisponible("No se pudo conectar con Mercado Pago", e);
        }
    }

    /** Devuelve el total de la orden ({@code POST /v1/orders/{id}/refund} sin cuerpo). Devuelve el id de la devolución. */
    public String reembolsar(String ordenId, String claveIdempotencia) {
        try {
            String respuesta = api.post().uri("/v1/orders/{id}/refund", ordenId)
                    .header("X-Idempotency-Key", claveIdempotencia)
                    .retrieve()
                    .body(String.class);
            JsonNode n = respuesta == null || respuesta.isBlank() ? JSON.createObjectNode() : JSON.readTree(respuesta);
            JsonNode devolucion = n.path("transactions").path("refunds").path(0);
            String id = devolucion.path("id").asText("");
            return id.isBlank() ? ordenId + ":refund" : id;
        } catch (RestClientResponseException e) {
            throw new MercadoPagoNoDisponible("Mercado Pago rechazó la devolución (" + e.getStatusCode().value() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new MercadoPagoNoDisponible("No se pudo conectar con Mercado Pago", e);
        } catch (Exception e) {
            throw new MercadoPagoNoDisponible("Respuesta de devolución ilegible", e);
        }
    }

    // ------------------------------------------------------------------

    static Orden leerOrden(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            if (n.path("id").asText("").isBlank() && n.path("data").path("id").isTextual()) {
                n = n.path("data"); // algunos errores traen la orden dentro de "data"
            }
            String estado = n.path("status").asText(null);
            String detalle = texto(n.path("status_detail"), null);
            JsonNode pagado = n.path("total_paid_amount");
            long centimos = pagado.isMissingNode() || pagado.isNull() || pagado.asText().isBlank()
                    ? 0 : new BigDecimal(pagado.asText()).movePointRight(2).longValue();
            String mensaje = "processed".equals(estado) ? "Pago aprobado" : mensajeRechazo(detalle);
            return new Orden(texto(n.path("id"), null), estado, detalle, centimos,
                    texto(n.path("external_reference"), null), mensaje, json);
        } catch (Exception e) {
            log.warn("Respuesta de Mercado Pago ilegible: {}", e.getMessage());
            throw new MercadoPagoNoDisponible("Respuesta de Mercado Pago ilegible", e);
        }
    }

    /** Un error 4xx: token inválido, tarjeta rechazada, etc. Si trae la orden, se lee la orden. */
    static Orden leerRechazo(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            JsonNode orden = n.path("data");
            if (orden.path("id").isTextual() && orden.path("status").isTextual()) {
                Orden o = leerOrden(JSON.writeValueAsString(orden));
                return new Orden(o.id(), "processed".equals(o.estado()) ? "failed" : o.estado(), o.detalle(), 0,
                        o.referenciaExterna(), mensajeRechazo(o.detalle()), json);
            }
            String codigo = texto(n.path("errors").path(0).path("code"), texto(n.path("error"), null));
            return new Orden(null, "failed", codigo, 0, null, mensajeRechazo(codigo), json);
        } catch (Exception e) {
            return new Orden(null, "failed", null, 0, null, MENSAJE_RECHAZO, json);
        }
    }

    /** Mensaje para el paciente según el motivo que da Mercado Pago. */
    static String mensajeRechazo(String detalle) {
        if (detalle == null) return MENSAJE_RECHAZO;
        String d = detalle.toLowerCase();
        if (d.contains("insufficient")) return "Fondos insuficientes. Usa otra tarjeta u otro medio de pago.";
        if (d.contains("security_code") || d.contains("bad_filled")) return "Revisa los datos de la tarjeta (número, fecha o CVV).";
        if (d.contains("call_for_authorize")) return "Tu banco necesita que autorices el pago. Llámalos o usa otro medio.";
        if (d.contains("expired")) return "El pago venció. Vuelve a intentarlo.";
        if (d.contains("otp") || d.contains("yape")) return "No se pudo validar el Yape. Revisa el número y el código de aprobación.";
        if (d.contains("high_risk") || d.contains("rejected")) return "El pago fue rechazado por seguridad. Usa otro medio de pago.";
        return MENSAJE_RECHAZO;
    }

    private static String texto(JsonNode n, String porDefecto) {
        return n == null || n.isMissingNode() || n.isNull() || n.asText().isBlank() ? porDefecto : n.asText();
    }

    private static String aJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo armar el JSON para Mercado Pago", e);
        }
    }
}

package com.odontosystem.OdontoSystem.citas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Cliente de la API de Culqi (RF13), en modo prueba o real según las llaves.
 * <ul>
 *   <li>La tarjeta o el Yape los tokeniza el frontend con Culqi Checkout y la llave PÚBLICA: el backend nunca ve
 *       el número de tarjeta, solo recibe el token ({@code tkn_…} o {@code ype_…}).</li>
 *   <li>El backend crea el cargo con la llave SECRETA ({@code POST /charges}) y, para un aviso (webhook), vuelve a
 *       consultar el cargo a Culqi ({@code GET /charges/{id}}): solo se cree lo que responde la API con nuestra llave,
 *       nunca lo que dice el cuerpo del aviso.</li>
 *   <li>Los montos van en céntimos ({@code 1600} = S/ 16.00).</li>
 * </ul>
 * Llaves: CULQI_PUBLIC_KEY y CULQI_SECRET_KEY (Railway / .env). Sin ellas la pasarela queda "no configurada".
 */
@Component
public class PasarelaCulqi {

    private static final Logger log = LoggerFactory.getLogger(PasarelaCulqi.class);
    static final String VENTA_EXITOSA = "venta_exitosa";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Un cargo de Culqi tal como lo devolvió la API. {@code id} es null si Culqi lo rechazó sin crearlo. */
    public record Cargo(String id, boolean aprobado, long montoCentimos, String moneda, String codigo,
                        String mensajeUsuario, Map<String, String> metadata, String json) {}

    /** Culqi no respondió (red, 5xx o llave inválida): no se sabe si cobró, así que hay que reintentar o esperar el aviso. */
    public static class CulqiNoDisponible extends RuntimeException {
        public CulqiNoDisponible(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    private final RestClient api;
    private final String llavePublica;
    private final boolean configurada;

    @Autowired
    public PasarelaCulqi(@Value("${app.pagos.culqi.llave-publica:}") String llavePublica,
                         @Value("${app.pagos.culqi.llave-secreta:}") String llaveSecreta,
                         @Value("${app.pagos.culqi.url:https://api.culqi.com/v2}") String url) {
        this(llavePublica, llaveSecreta, RestClient.builder().baseUrl(url));
    }

    PasarelaCulqi(String llavePublica, String llaveSecreta, RestClient.Builder builder) {
        this.llavePublica = llavePublica == null ? "" : llavePublica.strip();
        String secreta = llaveSecreta == null ? "" : llaveSecreta.strip();
        this.configurada = !this.llavePublica.isEmpty() && !secreta.isEmpty();
        this.api = builder.defaultHeader("Authorization", "Bearer " + secreta).build();
    }

    public boolean configurada() {
        return configurada;
    }

    /** La llave pública la necesita el frontend para abrir Culqi Checkout; no es secreta. */
    public String llavePublica() {
        return llavePublica;
    }

    /** true si las llaves son de prueba (pk_test / sk_test): ningún cobro es real. */
    public boolean modoPrueba() {
        return llavePublica.startsWith("pk_test");
    }

    /**
     * Crea el cargo. Si Culqi lo rechaza (tarjeta sin fondos, Yape inválido…) devuelve un {@link Cargo} no aprobado
     * con el mensaje para el paciente; si Culqi no responde lanza {@link CulqiNoDisponible}.
     */
    public Cargo cobrar(String token, long montoCentimos, String correo, String descripcion, Map<String, String> metadata) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("amount", montoCentimos);
        cuerpo.put("currency_code", "PEN");
        cuerpo.put("email", correo);
        cuerpo.put("source_id", token);
        cuerpo.put("description", descripcion.length() > 80 ? descripcion.substring(0, 80) : descripcion);
        cuerpo.put("metadata", metadata);
        try {
            String respuesta = api.post().uri("/charges")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(aJson(cuerpo))
                    .retrieve()
                    .body(String.class);
            return leerCargo(respuesta);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError() && e.getStatusCode().value() != 401) {
                return leerRechazo(e.getResponseBodyAsString());
            }
            throw new CulqiNoDisponible("Culqi respondió " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new CulqiNoDisponible("No se pudo conectar con Culqi", e);
        }
    }

    /** Consulta un cargo por su id. Vacío si Culqi no lo conoce. */
    public Optional<Cargo> consultarCargo(String cargoId) {
        try {
            String respuesta = api.get().uri("/charges/{id}", cargoId).retrieve().body(String.class);
            return Optional.of(leerCargo(respuesta));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 400) {
                return Optional.empty();
            }
            throw new CulqiNoDisponible("Culqi respondió " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new CulqiNoDisponible("No se pudo conectar con Culqi", e);
        }
    }

    /** Devuelve el monto de un cargo. Devuelve el id de la devolución en Culqi. */
    public String reembolsar(String cargoId, long montoCentimos) {
        Map<String, Object> cuerpo = Map.of("amount", montoCentimos, "charge_id", cargoId, "reason", "solicitud_comprador");
        try {
            String respuesta = api.post().uri("/refunds")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(aJson(cuerpo))
                    .retrieve()
                    .body(String.class);
            return JSON.readTree(respuesta).path("id").asText(null);
        } catch (RestClientResponseException e) {
            throw new CulqiNoDisponible("Culqi rechazó la devolución (" + e.getStatusCode().value() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new CulqiNoDisponible("No se pudo conectar con Culqi", e);
        } catch (Exception e) {
            throw new CulqiNoDisponible("Respuesta de devolución ilegible", e);
        }
    }

    // ------------------------------------------------------------------

    /** Un cargo creado es aprobado solo si su resultado es "venta_exitosa". */
    static Cargo leerCargo(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            if (!"charge".equals(n.path("object").asText())) {
                // p. ej. el banco pide 3-D Secure (action_code REVIEW): no hay cargo todavía
                return new Cargo(null, false, 0, null, n.path("action_code").asText(null),
                        texto(n.path("user_message"), "Tu banco pidió una verificación adicional. Intenta con otra tarjeta."),
                        Map.of(), json);
            }
            JsonNode resultado = n.path("outcome");
            Map<String, String> metadata = new LinkedHashMap<>();
            n.path("metadata").fields().forEachRemaining(e -> metadata.put(e.getKey(), e.getValue().asText()));
            return new Cargo(n.path("id").asText(null),
                    VENTA_EXITOSA.equals(resultado.path("type").asText()),
                    n.path("amount").asLong(),
                    n.path("currency_code").asText(null),
                    resultado.path("code").asText(null),
                    texto(resultado.path("user_message"), null),
                    metadata, json);
        } catch (Exception e) {
            log.warn("Respuesta de Culqi ilegible: {}", e.getMessage());
            throw new CulqiNoDisponible("Respuesta de Culqi ilegible", e);
        }
    }

    /** Un error de Culqi (object "error"): tarjeta rechazada, token vencido, etc. */
    static Cargo leerRechazo(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            String codigo = texto(n.path("decline_code"), texto(n.path("code"), n.path("type").asText(null)));
            return new Cargo(n.path("charge_id").asText(null), false, 0, null, codigo,
                    texto(n.path("user_message"), "El pago fue rechazado. Revisa los datos o usa otro medio de pago."),
                    Map.of(), json);
        } catch (Exception e) {
            return new Cargo(null, false, 0, null, null,
                    "El pago fue rechazado. Revisa los datos o usa otro medio de pago.", Map.of(), json);
        }
    }

    private static String texto(JsonNode n, String porDefecto) {
        return n == null || n.isMissingNode() || n.isNull() || n.asText().isBlank() ? porDefecto : n.asText();
    }

    private static String aJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo armar el JSON para Culqi", e);
        }
    }
}

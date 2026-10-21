package com.odontosystem.OdontoSystem.notificaciones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Envía notificaciones push con Firebase Cloud Messaging (API HTTP v1).
 * <ul>
 *   <li>Credencial: la cuenta de servicio de Firebase (archivo JSON) en FIREBASE_CREDENCIALES, como texto JSON o en
 *       Base64. Sin ella las push quedan apagadas y todo lo demás funciona igual.</li>
 *   <li>Con la llave privada de esa cuenta se firma un JWT y se cambia por un token de acceso de Google (dura 1 h y
 *       se reutiliza), sin librerías extra.</li>
 * </ul>
 */
@Component
public class EnviadorPush {

    private static final Logger log = LoggerFactory.getLogger(EnviadorPush.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALCANCE = "https://www.googleapis.com/auth/firebase.messaging";

    /** ENVIADO; INVALIDO = el token ya no existe (se borra); FALLIDO = error temporal. */
    public enum Resultado { ENVIADO, INVALIDO, FALLIDO }

    private final RestClient http;
    private final Clock clock;
    private final String proyecto;
    private final String correoCuenta;
    private final String urlToken;
    private final PrivateKey llave;

    private String tokenAcceso;
    private Instant venceEn = Instant.EPOCH;

    @Autowired
    public EnviadorPush(@Value("${app.push.firebase-credenciales:}") String credenciales, Clock clock) {
        this(credenciales, clock, RestClient.builder());
    }

    EnviadorPush(String credenciales, Clock clock, RestClient.Builder builder) {
        this.http = builder.build();
        this.clock = clock;
        String proyecto = null, correo = null, url = null;
        PrivateKey llave = null;
        if (credenciales != null && !credenciales.isBlank()) {
            try {
                String texto = credenciales.strip();
                if (!texto.startsWith("{")) {
                    texto = new String(Base64.getDecoder().decode(texto), StandardCharsets.UTF_8);
                }
                JsonNode cuenta = JSON.readTree(texto);
                proyecto = cuenta.path("project_id").asText(null);
                correo = cuenta.path("client_email").asText(null);
                url = cuenta.path("token_uri").asText("https://oauth2.googleapis.com/token");
                llave = leerLlave(cuenta.path("private_key").asText(""));
            } catch (Exception e) {
                log.error("FIREBASE_CREDENCIALES no es una cuenta de servicio válida: las push quedan apagadas ({})", e.getMessage());
                proyecto = null;
            }
        }
        this.proyecto = proyecto;
        this.correoCuenta = correo;
        this.urlToken = url;
        this.llave = llave;
        if (!activo()) {
            log.info("Notificaciones push desactivadas (falta FIREBASE_CREDENCIALES)");
        }
    }

    public boolean activo() {
        return proyecto != null && correoCuenta != null && llave != null;
    }

    /** Manda una push a un celular o navegador. Nunca lanza. */
    public Resultado enviar(String tokenDispositivo, String titulo, String cuerpo, Map<String, String> datos) {
        if (!activo()) {
            return Resultado.FALLIDO;
        }
        Map<String, Object> mensaje = Map.of("message", Map.of(
                "token", tokenDispositivo,
                "notification", Map.of("title", titulo, "body", cuerpo),
                "data", datos == null ? Map.of() : datos,
                "android", Map.of("priority", "high")));
        try {
            http.post().uri("https://fcm.googleapis.com/v1/projects/{p}/messages:send", proyecto)
                    .header("Authorization", "Bearer " + tokenDeAcceso())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsString(mensaje))
                    .retrieve()
                    .toBodilessEntity();
            return Resultado.ENVIADO;
        } catch (RestClientResponseException e) {
            String cuerpoError = e.getResponseBodyAsString();
            if (e.getStatusCode().value() == 404 || cuerpoError.contains("UNREGISTERED")
                    || (e.getStatusCode().value() == 400 && cuerpoError.contains("registration token"))) {
                return Resultado.INVALIDO;
            }
            log.warn("Firebase rechazó la push ({}): {}", e.getStatusCode().value(), cuerpoError);
            return Resultado.FALLIDO;
        } catch (Exception e) {
            log.warn("No se pudo enviar la push: {}", e.getMessage());
            return Resultado.FALLIDO;
        }
    }

    /** Token de acceso de Google para FCM; se pide de nuevo 1 minuto antes de que venza. */
    private synchronized String tokenDeAcceso() throws Exception {
        Instant ahora = clock.instant();
        if (tokenAcceso != null && ahora.isBefore(venceEn.minusSeconds(60))) {
            return tokenAcceso;
        }
        String jwt = Jwts.builder()
                .issuer(correoCuenta)
                .claim("scope", ALCANCE)
                .audience().add(urlToken).and()
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plusSeconds(3600)))
                .signWith(llave, Jwts.SIG.RS256)
                .compact();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", jwt);
        String respuesta = http.post().uri(urlToken)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        JsonNode n = JSON.readTree(respuesta);
        tokenAcceso = n.path("access_token").asText();
        venceEn = ahora.plusSeconds(n.path("expires_in").asLong(3600));
        return tokenAcceso;
    }

    static PrivateKey leerLlave(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");
        byte[] bytes = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }
}

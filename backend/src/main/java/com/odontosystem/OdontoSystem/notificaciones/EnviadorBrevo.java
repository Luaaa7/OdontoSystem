package com.odontosystem.OdontoSystem.notificaciones;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Envío por la API HTTPS de Brevo (plan gratuito: 300 correos al día).
 * Requiere BREVO_API_KEY y un remitente verificado en Brevo (CORREO_REMITENTE).
 */
@Component
@ConditionalOnProperty(name = "app.correo.proveedor", havingValue = "brevo")
public class EnviadorBrevo implements EnviadorCorreo {

    private final RestClient cliente;
    private final String remitente;
    private final String nombreRemitente;

    public EnviadorBrevo(@Value("${app.correo.brevo-api-key}") String apiKey,
                         @Value("${app.correo.remitente}") String remitente,
                         @Value("${app.correo.remitente-nombre}") String nombreRemitente) {
        if (apiKey == null || apiKey.isBlank() || remitente == null || remitente.isBlank()) {
            throw new IllegalStateException("CORREO_PROVEEDOR=brevo necesita BREVO_API_KEY y CORREO_REMITENTE");
        }
        this.cliente = RestClient.builder()
                .baseUrl("https://api.brevo.com/v3")
                .defaultHeader("api-key", apiKey)
                .build();
        this.remitente = remitente;
        this.nombreRemitente = nombreRemitente;
    }

    @Override
    public boolean activo() {
        return true;
    }

    @Override
    public void enviar(String para, String nombre, String asunto, String html) {
        Map<String, Object> cuerpo = Map.of(
                "sender", Map.of("name", nombreRemitente, "email", remitente),
                "to", List.of(Map.of("email", para, "name", nombre == null ? para : nombre)),
                "subject", asunto,
                "htmlContent", html);
        cliente.post().uri("/smtp/email")
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .retrieve()
                .toBodilessEntity(); // un 4xx/5xx lanza RestClientResponseException
    }
}

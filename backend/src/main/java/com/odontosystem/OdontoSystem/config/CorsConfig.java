package com.odontosystem.OdontoSystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * CORS: el navegador bloquea las llamadas de una página (ej. http://localhost:5173) a otro
 * servidor (ej. http://localhost:8080) salvo que el servidor diga explícitamente que las acepta.
 *
 * Los orígenes permitidos vienen de la propiedad app.cors.allowed-origins
 * (variable de entorno CORS_ALLOWED_ORIGINS en Railway), separados por coma.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") String origenesPermitidos) {

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origenesPermitidos.split(","))
                .map(String::trim)
                .filter(origen -> !origen.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // Los tokens van en el header Authorization, no en cookies: no hace falta allowCredentials.
        config.setMaxAge(Duration.ofHours(1)); // el navegador recuerda la respuesta del "preflight" 1 hora

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}

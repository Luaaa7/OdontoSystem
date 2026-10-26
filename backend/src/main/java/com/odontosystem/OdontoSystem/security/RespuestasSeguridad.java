package com.odontosystem.OdontoSystem.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

/**
 * Respuestas JSON de Spring Security, con el mismo formato que GlobalExceptionHandler:
 * <ul>
 *   <li>Sin token, token vencido o inválido → 401 (el frontend renueva con /api/auth/refresh).</li>
 *   <li>Token válido pero rol sin permiso → 403.</li>
 * </ul>
 * Antes ambos casos respondían 403 sin cuerpo.
 */
@Component
public class RespuestasSeguridad implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        escribir(response, request, HttpStatus.UNAUTHORIZED, "Inicia sesión o renueva tu token");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        escribir(response, request, HttpStatus.FORBIDDEN, "No tienes permiso para esta acción");
    }

    private static void escribir(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String mensaje)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String json = "{\"timestamp\":\"" + OffsetDateTime.now() + "\",\"status\":" + status.value()
                + ",\"error\":\"" + status.getReasonPhrase() + "\",\"mensaje\":\"" + mensaje
                + "\",\"path\":\"" + escapar(request.getRequestURI()) + "\"}";
        response.getWriter().write(json);
    }

    private static String escapar(String texto) {
        return texto == null ? "" : texto.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

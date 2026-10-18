package com.odontosystem.OdontoSystem.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

/**
 * Comprueba el ID token de Google (RF16): firma de Google, que no esté vencido y que fue emitido para nuestra app
 * (GOOGLE_CLIENT_ID; se pueden poner varios separados por coma, por ejemplo web y Android).
 */
@Component
public class VerificadorGoogle {

    private static final Logger log = LoggerFactory.getLogger(VerificadorGoogle.class);

    /** Lo que nos interesa del token de Google. */
    public record DatosGoogle(String sub, String correo, boolean correoVerificado, String nombre, String foto) {}

    private final GoogleIdTokenVerifier verificador;

    public VerificadorGoogle(@Value("${app.google.client-id:}") String clientIds) {
        List<String> audiencias = Arrays.stream(clientIds.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        this.verificador = audiencias.isEmpty() ? null
                : new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                        .setAudience(audiencias)
                        .build();
        if (audiencias.isEmpty()) {
            log.warn("GOOGLE_CLIENT_ID no está configurado: el inicio de sesión con Google responderá 503");
        }
    }

    public DatosGoogle verificar(String idToken) {
        if (verificador == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El inicio de sesión con Google no está disponible en este momento");
        }
        GoogleIdToken token;
        try {
            token = verificador.verify(idToken);
        } catch (IOException e) {
            log.warn("No se pudo consultar a Google para validar el token: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo validar tu cuenta de Google. Inténtalo en unos minutos.");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            token = null;
        }
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "El token de Google no es válido o ya venció");
        }
        GoogleIdToken.Payload p = token.getPayload();
        return new DatosGoogle(p.getSubject(), p.getEmail(), Boolean.TRUE.equals(p.getEmailVerified()),
                (String) p.get("name"), (String) p.get("picture"));
    }
}

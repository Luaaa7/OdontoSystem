package com.odontosystem.OdontoSystem.notificaciones;

import com.odontosystem.OdontoSystem.notificaciones.EnviadorPush.Resultado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Envío a Firebase (FCM v1) contra un servidor simulado, con una cuenta de servicio de prueba. */
class EnviadorPushTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-20T15:00:00Z"), ZoneOffset.UTC);

    private static String cuentaDePrueba() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair par = g.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(par.getPrivate().getEncoded()) + "\\n-----END PRIVATE KEY-----\\n";
        return "{\"type\":\"service_account\",\"project_id\":\"odontosystem-test\","
                + "\"client_email\":\"push@odontosystem-test.iam.gserviceaccount.com\","
                + "\"token_uri\":\"https://oauth2.googleapis.com/token\",\"private_key\":\"" + pem + "\"}";
    }

    @Test
    @DisplayName("Sin credenciales queda apagado")
    void apagado() {
        assertThat(new EnviadorPush("", RELOJ).activo()).isFalse();
        assertThat(new EnviadorPush("no es json", RELOJ).activo()).isFalse();
    }

    @Test
    @DisplayName("Pide el token de acceso a Google una vez y envía a FCM v1 con título, cuerpo y datos")
    void envia() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        EnviadorPush push = new EnviadorPush(cuentaDePrueba(), RELOJ, builder);
        assertThat(push.activo()).isTrue();

        servidor.expect(requestTo("https://oauth2.googleapis.com/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"ya29.prueba\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        for (int i = 0; i < 2; i++) {
            servidor.expect(requestTo("https://fcm.googleapis.com/v1/projects/odontosystem-test/messages:send"))
                    .andExpect(header("Authorization", "Bearer ya29.prueba"))
                    .andExpect(jsonPath("$.message.token").value("tok-1"))
                    .andExpect(jsonPath("$.message.notification.title").value("Recordatorio"))
                    .andExpect(jsonPath("$.message.data.tipo").value("RECORDATORIO_CITA"))
                    .andRespond(withSuccess("{\"name\":\"projects/x/messages/1\"}", MediaType.APPLICATION_JSON));
        }

        assertThat(push.enviar("tok-1", "Recordatorio", "Tu cita es mañana", Map.of("tipo", "RECORDATORIO_CITA")))
                .isEqualTo(Resultado.ENVIADO);
        assertThat(push.enviar("tok-1", "Recordatorio", "Tu cita es mañana", Map.of("tipo", "RECORDATORIO_CITA")))
                .isEqualTo(Resultado.ENVIADO); // reutiliza el token de acceso
        servidor.verify();
    }

    @Test
    @DisplayName("Token de celular que ya no existe (404 UNREGISTERED) → INVALIDO")
    void tokenInvalido() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        EnviadorPush push = new EnviadorPush(cuentaDePrueba(), RELOJ, builder);

        servidor.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess("{\"access_token\":\"ya29.prueba\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo("https://fcm.googleapis.com/v1/projects/odontosystem-test/messages:send"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}"));

        assertThat(push.enviar("tok-viejo", "T", "C", Map.of())).isEqualTo(Resultado.INVALIDO);
    }
}

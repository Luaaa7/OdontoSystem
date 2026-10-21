package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.PasarelaCulqi.Cargo;
import com.odontosystem.OdontoSystem.citas.PasarelaCulqi.CulqiNoDisponible;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Cliente de Culqi contra un servidor simulado: qué manda y cómo lee cada respuesta. */
class PasarelaCulqiTest {

    private static final String URL = "https://api.culqi.com/v2";
    private MockRestServiceServer servidor;
    private PasarelaCulqi culqi;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        servidor = MockRestServiceServer.bindTo(builder).build();
        culqi = new PasarelaCulqi("pk_test_publica", "sk_test_secreta", builder);
    }

    private static final String CARGO_OK = """
            {"object":"charge","id":"chr_test_123","amount":1600,"currency_code":"PEN","email":"lucia@correo.pe",
             "outcome":{"type":"venta_exitosa","code":"AUT0000","merchant_message":"OK","user_message":"Su compra ha sido exitosa."},
             "metadata":{"cita_id":"c1","intento_id":"i1"}}
            """;

    @Test
    @DisplayName("Cobro aprobado: manda monto en céntimos, PEN, token y llave secreta; lee id, monto y metadata")
    void cobroAprobado() {
        servidor.expect(requestTo(URL + "/charges")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk_test_secreta"))
                .andExpect(jsonPath("$.amount").value(1600))
                .andExpect(jsonPath("$.currency_code").value("PEN"))
                .andExpect(jsonPath("$.source_id").value("tkn_test_abc"))
                .andExpect(jsonPath("$.metadata.cita_id").value("c1"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(CARGO_OK));

        Cargo c = culqi.cobrar("tkn_test_abc", 1600, "lucia@correo.pe", "Deposito", Map.of("cita_id", "c1"));

        assertThat(c.aprobado()).isTrue();
        assertThat(c.id()).isEqualTo("chr_test_123");
        assertThat(c.montoCentimos()).isEqualTo(1600);
        assertThat(c.metadata()).containsEntry("intento_id", "i1");
        servidor.verify();
    }

    @Test
    @DisplayName("Tarjeta rechazada (402): no aprobado, con el mensaje de Culqi para el paciente")
    void cobroRechazado() {
        servidor.expect(requestTo(URL + "/charges")).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"object\":\"error\",\"type\":\"card_error\",\"charge_id\":\"chr_test_9\",\"decline_code\":\"insufficient_funds\","
                        + "\"user_message\":\"Su tarjeta no tiene fondos suficientes.\"}"));

        Cargo c = culqi.cobrar("tkn_test_abc", 1600, "lucia@correo.pe", "Deposito", Map.of());

        assertThat(c.aprobado()).isFalse();
        assertThat(c.codigo()).isEqualTo("insufficient_funds");
        assertThat(c.mensajeUsuario()).contains("fondos suficientes");
    }

    @Test
    @DisplayName("El banco pide 3-D Secure: no hay cargo, queda no aprobado")
    void tresDSecure() {
        servidor.expect(requestTo(URL + "/charges")).andRespond(withSuccess(
                "{\"action_code\":\"REVIEW\",\"user_message\":\"Requiere autenticación\"}", MediaType.APPLICATION_JSON));

        Cargo c = culqi.cobrar("tkn_test_abc", 1600, "lucia@correo.pe", "Deposito", Map.of());

        assertThat(c.aprobado()).isFalse();
        assertThat(c.id()).isNull();
        assertThat(c.codigo()).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("Culqi caído (500): CulqiNoDisponible, porque no se sabe si cobró")
    void culqiCaido() {
        servidor.expect(requestTo(URL + "/charges")).andRespond(withServerError());

        assertThatThrownBy(() -> culqi.cobrar("tkn_test_abc", 1600, "lucia@correo.pe", "Deposito", Map.of()))
                .isInstanceOf(CulqiNoDisponible.class);
    }

    @Test
    @DisplayName("Consultar un cargo: lo lee si existe; vacío si Culqi no lo conoce")
    void consultar() {
        servidor.expect(requestTo(URL + "/charges/chr_test_123")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(CARGO_OK, MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(URL + "/charges/chr_falso")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(culqi.consultarCargo("chr_test_123")).get().extracting(Cargo::aprobado).isEqualTo(true);
        assertThat(culqi.consultarCargo("chr_falso")).isEmpty();
    }

    @Test
    @DisplayName("Devolución: manda cargo, monto y motivo; devuelve el id de Culqi")
    void reembolso() {
        servidor.expect(requestTo(URL + "/refunds")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.charge_id").value("chr_test_123"))
                .andExpect(jsonPath("$.amount").value(1600))
                .andExpect(jsonPath("$.reason").value("solicitud_comprador"))
                .andRespond(withSuccess("{\"object\":\"refund\",\"id\":\"ref_test_1\"}", MediaType.APPLICATION_JSON));

        assertThat(culqi.reembolsar("chr_test_123", 1600)).isEqualTo("ref_test_1");
    }

    @Test
    @DisplayName("Sin llaves no está configurada; con pk_test es modo prueba")
    void configuracion() {
        assertThat(culqi.configurada()).isTrue();
        assertThat(culqi.modoPrueba()).isTrue();
        assertThat(new PasarelaCulqi("", "sk", RestClient.builder()).configurada()).isFalse();
    }
}

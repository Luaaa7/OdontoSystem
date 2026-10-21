package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.PasarelaMercadoPago.MercadoPagoNoDisponible;
import com.odontosystem.OdontoSystem.citas.PasarelaMercadoPago.Orden;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Cliente de la API de Orders de Mercado Pago contra un servidor simulado. */
class PasarelaMercadoPagoTest {

    private static final String URL = "https://api.mercadopago.com";
    private MockRestServiceServer servidor;
    private PasarelaMercadoPago mp;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        servidor = MockRestServiceServer.bindTo(builder).build();
        mp = new PasarelaMercadoPago("APP_USR-publica", "APP_USR-secreto", builder, true);
    }

    private static final String ORDEN_OK = """
            {"id":"ORD01JS2V6CM8KJ0EC4H502TGK1WP","type":"online","processing_mode":"automatic",
             "external_reference":"intento-1","total_amount":"16.00","total_paid_amount":"16.00",
             "status":"processed","status_detail":"accredited",
             "transactions":{"payments":[{"id":"PAY01","amount":"16.00","status":"processed","status_detail":"accredited"}]}}
            """;

    @Test
    @DisplayName("Tarjeta aprobada: manda monto con 2 decimales, medio, token, cuotas e idempotencia; lee la orden")
    void cobroConTarjeta() {
        servidor.expect(requestTo(URL + "/v1/orders")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer APP_USR-secreto"))
                .andExpect(header("X-Idempotency-Key", "clave-1"))
                .andExpect(jsonPath("$.processing_mode").value("automatic"))
                .andExpect(jsonPath("$.total_amount").value("16.00"))
                .andExpect(jsonPath("$.external_reference").value("intento-1"))
                .andExpect(jsonPath("$.payer.email").value("lucia@correo.pe"))
                .andExpect(jsonPath("$.transactions.payments[0].amount").value("16.00"))
                .andExpect(jsonPath("$.transactions.payments[0].payment_method.id").value("visa"))
                .andExpect(jsonPath("$.transactions.payments[0].payment_method.type").value("credit_card"))
                .andExpect(jsonPath("$.transactions.payments[0].payment_method.token").value("tok123"))
                .andExpect(jsonPath("$.transactions.payments[0].payment_method.installments").value(1))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(ORDEN_OK));

        Orden o = mp.cobrar("tok123", "VISA", 1600, "lucia@correo.pe", "intento-1", "clave-1");

        assertThat(o.aprobada()).isTrue();
        assertThat(o.rechazada()).isFalse();
        assertThat(o.id()).isEqualTo("ORD01JS2V6CM8KJ0EC4H502TGK1WP");
        assertThat(o.montoPagadoCentimos()).isEqualTo(1600);
        assertThat(o.referenciaExterna()).isEqualTo("intento-1");
        servidor.verify();
    }

    @Test
    @DisplayName("Yape: medio yape de tipo debit_card y sin cuotas")
    void cobroConYape() {
        servidor.expect(requestTo(URL + "/v1/orders"))
                .andExpect(jsonPath("$.transactions.payments[0].payment_method.id").value("yape"))
                .andExpect(jsonPath("$.transactions.payments[0].payment_method.type").value("debit_card"))
                .andExpect(jsonPath("$.transactions.payments[0].payment_method.installments").doesNotExist())
                .andRespond(withSuccess(ORDEN_OK, MediaType.APPLICATION_JSON));

        assertThat(mp.cobrar("yape-tok", "yape", 1600, "lucia@correo.pe", "intento-1", "clave-1").aprobada()).isTrue();
    }

    @Test
    @DisplayName("Tipo de medio: yape y débito (deb…) son debit_card; el resto credit_card")
    void tipos() {
        assertThat(PasarelaMercadoPago.tipoDeMedio("yape")).isEqualTo("debit_card");
        assertThat(PasarelaMercadoPago.tipoDeMedio("debvisa")).isEqualTo("debit_card");
        assertThat(PasarelaMercadoPago.tipoDeMedio("master")).isEqualTo("credit_card");
    }

    @Test
    @DisplayName("Pago rechazado (4xx con la orden en data): orden rechazada con mensaje para el paciente")
    void rechazado() {
        servidor.expect(requestTo(URL + "/v1/orders")).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"errors":[{"code":"failed","message":"The following transactions failed"}],
                         "data":{"id":"ORD02","status":"failed","status_detail":"failed","total_paid_amount":"0.00",
                                 "external_reference":"intento-1",
                                 "transactions":{"payments":[{"id":"PAY02","status":"failed","status_detail":"insufficient_amount"}]}}}
                        """));

        Orden o = mp.cobrar("tok", "master", 1600, "lucia@correo.pe", "intento-1", "clave-1");

        assertThat(o.rechazada()).isTrue();
        assertThat(o.aprobada()).isFalse();
        assertThat(o.id()).isEqualTo("ORD02");
        assertThat(o.mensajeUsuario()).isNotBlank();
    }

    @Test
    @DisplayName("Token inválido (400 sin orden): rechazo genérico")
    void tokenInvalido() {
        servidor.expect(requestTo(URL + "/v1/orders")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"code\":\"invalid_card_token\",\"message\":\"bad token\"}]}"));

        Orden o = mp.cobrar("tok", "visa", 1600, "lucia@correo.pe", "intento-1", "clave-1");

        assertThat(o.rechazada()).isTrue();
        assertThat(o.id()).isNull();
        assertThat(o.mensajeUsuario()).isEqualTo(PasarelaMercadoPago.MENSAJE_RECHAZO);
    }

    @Test
    @DisplayName("Mercado Pago caído (500) o credencial inválida (401): no se sabe si cobró → MercadoPagoNoDisponible")
    void noDisponible() {
        servidor.expect(requestTo(URL + "/v1/orders")).andRespond(withServerError());
        assertThatThrownBy(() -> mp.cobrar("tok", "visa", 1600, "a@b.pe", "i", "k"))
                .isInstanceOf(MercadoPagoNoDisponible.class);

        servidor.reset();
        servidor.expect(requestTo(URL + "/v1/orders")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> mp.cobrar("tok", "visa", 1600, "a@b.pe", "i", "k"))
                .isInstanceOf(MercadoPagoNoDisponible.class);
    }

    @Test
    @DisplayName("Consultar: lee la orden; 404 → vacío")
    void consultar() {
        servidor.expect(requestTo(URL + "/v1/orders/ORD01JS2V6CM8KJ0EC4H502TGK1WP")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ORDEN_OK, MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(URL + "/v1/orders/ORD404")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(mp.consultarOrden("ORD01JS2V6CM8KJ0EC4H502TGK1WP")).get().extracting(Orden::aprobada).isEqualTo(true);
        assertThat(mp.consultarOrden("ORD404")).isEmpty();
    }

    @Test
    @DisplayName("Devolución total: POST /refund con idempotencia; devuelve el id de la devolución o uno derivado")
    void reembolsar() {
        servidor.expect(requestTo(URL + "/v1/orders/ORD01/refund")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Idempotency-Key", "reembolso-1"))
                .andRespond(withSuccess("{\"id\":\"ORD01\",\"status\":\"refunded\",\"transactions\":{\"refunds\":[{\"id\":\"REF01\"}]}}",
                        MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(URL + "/v1/orders/ORD02/refund"))
                .andRespond(withSuccess("{\"id\":\"ORD02\",\"status\":\"refunded\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(URL + "/v1/orders/ORD03/refund")).andRespond(withServerError());

        assertThat(mp.reembolsar("ORD01", "reembolso-1")).isEqualTo("REF01");
        assertThat(mp.reembolsar("ORD02", "reembolso-2")).isEqualTo("ORD02:refund");
        assertThatThrownBy(() -> mp.reembolsar("ORD03", "reembolso-3")).isInstanceOf(MercadoPagoNoDisponible.class);
    }

    @Test
    @DisplayName("Configurada solo con Public Key y Access Token")
    void configuracion() {
        assertThat(mp.configurada()).isTrue();
        assertThat(mp.llavePublica()).isEqualTo("APP_USR-publica");
        assertThat(mp.modoPrueba()).isTrue();
        assertThat(PasarelaMercadoPago.sinConfigurar().configurada()).isFalse();
    }

    @Test
    @DisplayName("Mensajes de rechazo según el motivo")
    void mensajes() {
        assertThat(PasarelaMercadoPago.mensajeRechazo("insufficient_amount")).contains("Fondos insuficientes");
        assertThat(PasarelaMercadoPago.mensajeRechazo("bad_filled_security_code")).contains("CVV");
        assertThat(PasarelaMercadoPago.mensajeRechazo(null)).isEqualTo(PasarelaMercadoPago.MENSAJE_RECHAZO);
    }
}

package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.service.ClienteFactiliza.ConsultaDni;
import com.odontosystem.OdontoSystem.service.ClienteFactiliza.Estado;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Cliente de Factiliza (RENIEC) contra un servidor simulado, con el cupo mensual de consultas. */
class ClienteFactilizaTest {

    private static final String URL = "https://api.factiliza.com";
    private MockRestServiceServer servidor;
    private ControlConsumoApi consumo;
    private ClienteFactiliza factiliza;

    private static final String DNI_OK = """
            {"status":200,"success":true,"message":"Exito",
             "data":{"numero":"45879632","nombres":"ANA MARIA","apellido_paterno":"TORRES","apellido_materno":"RIOS",
                     "nombre_completo":"TORRES RIOS, ANA MARIA","direccion":"AV. SIEMPRE VIVA 123"}}
            """;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        servidor = MockRestServiceServer.bindTo(builder).build();
        consumo = mock(ControlConsumoApi.class);
        when(consumo.reservar(anyString(), anyInt())).thenReturn(true);
        factiliza = new ClienteFactiliza("tok_factiliza", builder, 100, consumo);
    }

    @Test
    @DisplayName("DNI encontrado (200): manda el token Bearer, descuenta 1 del cupo y lee nombres y apellidos")
    void encontrado() {
        servidor.expect(requestTo(URL + "/v1/dni/info/45879632")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok_factiliza"))
                .andRespond(withSuccess(DNI_OK, MediaType.APPLICATION_JSON));

        ConsultaDni c = factiliza.consultarDni("45879632");

        assertThat(c.estado()).isEqualTo(Estado.ENCONTRADO);
        assertThat(c.persona().nombreCompleto()).isEqualTo("ANA MARIA TORRES RIOS");
        verify(consumo).reservar("FACTILIZA_DNI", 100);
        verify(consumo, never()).liberar(anyString());
    }

    @Test
    @DisplayName("Sin cupo este mes: no llama a Factiliza y responde SIN_CUPO")
    void sinCupo() {
        when(consumo.reservar(anyString(), anyInt())).thenReturn(false);

        assertThat(factiliza.consultarDni("45879632").estado()).isEqualTo(Estado.SIN_CUPO);
        servidor.verify(); // ninguna llamada esperada ni hecha
    }

    @Test
    @DisplayName("DNI inexistente (404) o respuesta success=false: NO_ENCONTRADO (la consulta sí se cuenta)")
    void noEncontrado() {
        servidor.expect(requestTo(URL + "/v1/dni/info/11111111")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        servidor.expect(requestTo(URL + "/v1/dni/info/22222222"))
                .andRespond(withSuccess("{\"status\":404,\"success\":false,\"message\":\"No encontrado\"}", MediaType.APPLICATION_JSON));

        assertThat(factiliza.consultarDni("11111111").estado()).isEqualTo(Estado.NO_ENCONTRADO);
        assertThat(factiliza.consultarDni("22222222").estado()).isEqualTo(Estado.NO_ENCONTRADO);
        verify(consumo, never()).liberar(anyString());
    }

    @Test
    @DisplayName("Token inválido (401) o Factiliza caído (500): NO_DISPONIBLE y la consulta vuelve al cupo")
    void noDisponible() {
        servidor.expect(requestTo(URL + "/v1/dni/info/45879632")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        servidor.expect(requestTo(URL + "/v1/dni/info/45879632")).andRespond(withServerError());

        ConsultaDni tokenMalo = factiliza.consultarDni("45879632");
        ConsultaDni caido = factiliza.consultarDni("45879632");

        assertThat(tokenMalo.estado()).isEqualTo(Estado.NO_DISPONIBLE);
        assertThat(tokenMalo.detalle()).contains("token");
        assertThat(caido.estado()).isEqualTo(Estado.NO_DISPONIBLE);
        verify(consumo, org.mockito.Mockito.times(2)).liberar("FACTILIZA_DNI");
    }

    @Test
    @DisplayName("DNI mal escrito o sin token configurado: no gasta consultas")
    void validaciones() {
        assertThat(factiliza.consultarDni("1234").estado()).isEqualTo(Estado.NO_ENCONTRADO);
        assertThat(factiliza.consultarDni("abcdefgh").estado()).isEqualTo(Estado.NO_ENCONTRADO);
        ClienteFactiliza sinToken = new ClienteFactiliza("  ", RestClient.builder(), 100, consumo);
        assertThat(sinToken.configurado()).isFalse();
        assertThat(sinToken.consultarDni("45879632").estado()).isEqualTo(Estado.NO_CONFIGURADO);
        verify(consumo, never()).reservar(anyString(), anyInt());
    }

    @Test
    @DisplayName("Resumen de consumo: usadas / límite")
    void resumen() {
        when(consumo.usadas("FACTILIZA_DNI")).thenReturn(7);
        assertThat(factiliza.resumenConsumo()).isEqualTo("7/100");
    }
}

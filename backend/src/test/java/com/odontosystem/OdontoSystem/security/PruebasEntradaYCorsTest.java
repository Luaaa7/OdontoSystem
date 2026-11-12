package com.odontosystem.OdontoSystem.security;

import com.odontosystem.OdontoSystem.dto.RegistroRequest;
import com.odontosystem.OdontoSystem.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de seguridad del plan de auditoría que no cubrían otras clases:
 * B9 (validación de entradas / asignación de rol), I1 (CORS) y B11 (IP tomada de X-Forwarded-For).
 */
class PruebasEntradaYCorsTest {

    /** B9: el registro no acepta un rol que no existe y un JSON roto no termina en 500. */
    @Nested
    class ValidacionDeEntradas {

        private MockMvc mvc;

        @BeforeEach
        void setUp() {
            mvc = MockMvcBuilders.standaloneSetup(new RegistroDePrueba())
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }

        @Test
        @DisplayName("B9: registrarse con rol \"ADMIN\" → 400 (no existe ese rol) y sin detalles internos")
        void rolInexistente() throws Exception {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"correo\":\"x@prueba.pe\",\"rol\":\"ADMIN\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(content().string(not(containsString("RolUsuario"))))
                    .andExpect(content().string(not(containsString("jackson"))));
        }

        @Test
        @DisplayName("B9: JSON mal formado → 400, no 500")
        void jsonRoto() throws Exception {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"rol\":"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("B9: campos vacíos o fuera de rango → 400 con el campo que falla")
        void camposInvalidos() throws Exception {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rol\":\"PACIENTE\",\"password\":\"123\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.mensaje").value(containsString("password")));
        }
    }

    /** I1: solo los orígenes configurados (aquí http://localhost:5173) pueden llamar a la API desde un navegador. */
    @Nested
    @SpringJUnitWebConfig(MatrizControlAccesoTest.Contexto.class)
    class Cors {

        @Autowired
        private WebApplicationContext contexto;
        private MockMvc mvc;

        @BeforeEach
        void setUp() {
            mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
        }

        @Test
        @DisplayName("I1: preflight desde http://evil.com → 403 y sin Access-Control-Allow-Origin")
        void origenNoPermitido() throws Exception {
            mvc.perform(options("/api/auth/login").header("Origin", "http://evil.com")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }

        @Test
        @DisplayName("I1: petición real desde http://evil.com → 403 (el navegador no recibe la respuesta)")
        void peticionDesdeOrigenNoPermitido() throws Exception {
            mvc.perform(get("/api/odontologos").header("Origin", "http://evil.com"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }

        @Test
        @DisplayName("I1: preflight desde el frontend permitido → 200 con su origen en Access-Control-Allow-Origin")
        void origenPermitido() throws Exception {
            mvc.perform(options("/api/auth/login").header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        }

        @Test
        @DisplayName("I1: una cabecera no permitida (X-Evil) desde el origen permitido → 403")
        void cabeceraNoPermitida() throws Exception {
            mvc.perform(options("/api/auth/login").header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "X-Evil"))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * B11 (evidencia): con server.forward-headers-strategy=framework, Spring toma la IP del cliente del PRIMER valor de
     * X-Forwarded-For. Si el proxy de Railway agrega la IP real al final en vez de reemplazar la cabecera, un atacante
     * podría cambiar de "IP" en cada intento y saltarse el bloqueo de 20 fallos por IP (el de 5 por cuenta sigue).
     * Esta prueba documenta el comportamiento; el riesgo se confirma (o descarta) en el entorno de Railway.
     */
    @Nested
    class IpDelCliente {

        @Test
        @DisplayName("B11: la IP que ve el backend sale del primer valor de X-Forwarded-For")
        void ipDesdeXForwardedFor() throws Exception {
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new EcoIp())
                    .addFilters(new ForwardedHeaderFilter())
                    .build();
            mvc.perform(get("/eco-ip").header("X-Forwarded-For", "203.0.113.7, 10.0.0.1"))
                    .andExpect(content().string("203.0.113.7"));
        }
    }

    @RestController
    static class RegistroDePrueba {
        @PostMapping("/api/auth/register")
        String registrar(@Valid @RequestBody RegistroRequest request) {
            return "ok";
        }
    }

    @RestController
    static class EcoIp {
        @GetMapping("/eco-ip")
        String ip(HttpServletRequest request) {
            return request.getRemoteAddr();
        }
    }
}

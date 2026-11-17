package com.odontosystem.OdontoSystem.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Monitoreo (S16): /actuator/prometheus solo responde a las credenciales de Grafana, nunca a una cuenta de la app,
 * y queda cerrado si no se configuró una contraseña. Un controlador de prueba responde 200 en esa ruta:
 * si la petición llega, la seguridad la dejó pasar.
 *
 * {@code @ExtendWith} en la clase externa hace que Spring Boot la reconozca como prueba: así sus configuraciones internas
 * (cadena y controlador de prueba) no se cargan en la app completa de BaseDeDatosIntegracionTest.
 */
@ExtendWith(SpringExtension.class)
class MetricasSecurityConfigTest {

    static final String CLAVE = "clave-de-prueba-larga-123";

    @Nested
    @SpringJUnitWebConfig(ConCredenciales.Contexto.class)
    @TestPropertySource(properties = {"app.metricas.usuario=grafana", "app.metricas.password=" + CLAVE})
    class ConCredenciales {

        @Autowired
        WebApplicationContext contexto;
        MockMvc mvc;

        @BeforeEach
        void setUp() {
            mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
        }

        @Test
        @DisplayName("Sin credenciales → 401")
        void sinCredenciales() throws Exception {
            mvc.perform(get(MetricasSecurityConfig.RUTA)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Contraseña incorrecta → 401")
        void claveIncorrecta() throws Exception {
            mvc.perform(get(MetricasSecurityConfig.RUTA).with(httpBasic("grafana", "otra-clave-cualquiera-xx")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Una cuenta de la app (paciente) no sirve para leer las métricas → 401")
        void cuentaDeLaApp() throws Exception {
            mvc.perform(get(MetricasSecurityConfig.RUTA).with(httpBasic("paciente@prueba.pe", "clave-del-paciente")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Usuario y contraseña de Grafana → 200")
        void credencialesDeGrafana() throws Exception {
            mvc.perform(get(MetricasSecurityConfig.RUTA).with(httpBasic("grafana", CLAVE))).andExpect(status().isOk());
        }

        @Configuration
        @EnableWebMvc
        @EnableWebSecurity
        @Import({MetricasSecurityConfig.class, RutaDePrueba.class, CadenaDeLaApp.class})
        static class Contexto {
        }
    }

    @Nested
    @SpringJUnitWebConfig(SinContrasena.Contexto.class)
    @TestPropertySource(properties = {"app.metricas.usuario=grafana", "app.metricas.password="})
    class SinContrasena {

        @Autowired
        WebApplicationContext contexto;

        @Test
        @DisplayName("Sin METRICAS_PASSWORD el endpoint queda cerrado, con o sin credenciales")
        void cerrado() throws Exception {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
            mvc.perform(get(MetricasSecurityConfig.RUTA).with(httpBasic("grafana", "cualquier-clave-larga-1")))
                    .andExpect(status().is4xxClientError());
            mvc.perform(get(MetricasSecurityConfig.RUTA)).andExpect(status().is4xxClientError());
        }

        @Configuration
        @EnableWebMvc
        @EnableWebSecurity
        @Import({MetricasSecurityConfig.class, RutaDePrueba.class, CadenaDeLaApp.class})
        static class Contexto {
        }
    }

    @RestController
    static class RutaDePrueba {
        @GetMapping(MetricasSecurityConfig.RUTA)
        String metricas() {
            return "# metricas";
        }
    }

    /** Imita la cadena de la API con una cuenta de la app: la cadena de métricas no debe aceptarla. */
    @Configuration
    static class CadenaDeLaApp {
        @Bean
        InMemoryUserDetailsManager usuariosDeLaApp() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("paciente@prueba.pe").password("{noop}clave-del-paciente").roles("PACIENTE").build());
        }

        @Bean
        SecurityFilterChain cadenaApi(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults()).build();
        }
    }
}

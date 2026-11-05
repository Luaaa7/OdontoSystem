package com.odontosystem.OdontoSystem.security;

import com.odontosystem.OdontoSystem.config.CorsConfig;
import com.odontosystem.OdontoSystem.config.SecurityConfig;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OWASP A01 (Broken Access Control): matriz de roles (invitado, paciente, odontólogo) contra las rutas de la API.
 *
 * Se levanta la configuración de seguridad REAL ({@link SecurityConfig}) con un controlador de prueba que responde
 * 200 a cualquier ruta: si la petición llega al controlador, la seguridad la dejó pasar. Así se prueba solo la regla
 * de acceso, sin base de datos. Las reglas de "solo lo tuyo" (un paciente no ve la cita de otro) las cubren las
 * pruebas de cada servicio, que responden 404.
 *
 * Al terminar escribe la matriz en target/seguridad/matriz-control-acceso.md (evidencia para el informe).
 */
@SpringJUnitWebConfig(MatrizControlAccesoTest.Contexto.class)
class MatrizControlAccesoTest {

    enum Rol { INVITADO, PACIENTE, ODONTOLOGO }

    private static final int OK = 200, SIN_SESION = 401, PROHIBIDO = 403;
    private static final Map<String, Map<Rol, Integer>> MATRIZ = new TreeMap<>();

    @Autowired
    private WebApplicationContext contexto;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
    }

    /** Ruta, método y lo que debe pasar para invitado, paciente y odontólogo. */
    static Stream<Arguments> matriz() {
        return Stream.of(
                // Públicas: cualquiera explora el catálogo antes de registrarse
                fila("GET", "/api/odontologos", OK, OK, OK),
                fila("GET", "/api/odontologos/00000000-0000-0000-0000-000000000001/disponibilidad", OK, OK, OK),
                fila("GET", "/api/public/pagos/config", OK, OK, OK),
                fila("POST", "/api/auth/login", OK, OK, OK),
                // Solo con sesión, cualquier rol
                fila("GET", "/api/usuarios/yo", SIN_SESION, OK, OK),
                fila("GET", "/api/usuarios/yo/notificaciones", SIN_SESION, OK, OK),
                fila("GET", "/api/citas/mias", SIN_SESION, OK, OK),
                // Solo paciente
                fila("POST", "/api/citas", SIN_SESION, OK, PROHIBIDO),
                fila("POST", "/api/citas/00000000-0000-0000-0000-000000000001/pagar", SIN_SESION, OK, PROHIBIDO),
                fila("GET", "/api/historias/mias", SIN_SESION, OK, PROHIBIDO),
                fila("GET", "/api/usuarios/yo/reputacion", SIN_SESION, OK, PROHIBIDO),
                // Solo odontólogo (el equivalente a la "ruta de administrador" de la consigna)
                fila("GET", "/api/odontologos/yo/pacientes", SIN_SESION, PROHIBIDO, OK),
                fila("GET", "/api/pacientes/00000000-0000-0000-0000-000000000001", SIN_SESION, PROHIBIDO, OK),
                fila("GET", "/api/odontologos/yo/liquidaciones", SIN_SESION, PROHIBIDO, OK),
                fila("PATCH", "/api/odontologos/yo/citas/00000000-0000-0000-0000-000000000001/confirmar", SIN_SESION, PROHIBIDO, OK),
                fila("POST", "/api/citas/00000000-0000-0000-0000-000000000001/historia", SIN_SESION, PROHIBIDO, OK));
    }

    private static Arguments fila(String metodo, String ruta, int invitado, int paciente, int odontologo) {
        return Arguments.of(metodo, ruta, invitado, paciente, odontologo);
    }

    @ParameterizedTest(name = "{0} {1} → invitado {2}, paciente {3}, odontólogo {4}")
    @MethodSource("matriz")
    @DisplayName("Matriz de control de acceso por rol")
    void controlDeAcceso(String metodo, String ruta, int invitado, int paciente, int odontologo) throws Exception {
        comprobar(metodo, ruta, Rol.INVITADO, invitado);
        comprobar(metodo, ruta, Rol.PACIENTE, paciente);
        comprobar(metodo, ruta, Rol.ODONTOLOGO, odontologo);
    }

    @Test
    @DisplayName("Prueba obligatoria (consigna A01): un PACIENTE que entra por URL a una ruta de ODONTÓLOGO recibe 403 en JSON")
    void pacienteEnRutaDeOdontologo() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/odontologos/yo/pacientes").with(user("paciente@prueba.pe").roles("PACIENTE")))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")));
    }

    @Test
    @DisplayName("Cabeceras de seguridad que pide OWASP ZAP en todas las respuestas")
    void cabecerasDeSeguridad() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/odontologos"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", org.hamcrest.Matchers.containsString("camera=()")));
    }

    private void comprobar(String metodo, String ruta, Rol rol, int esperado) throws Exception {
        MockHttpServletRequestBuilder peticion = request(HttpMethod.valueOf(metodo), ruta);
        if (rol != Rol.INVITADO) {
            peticion = peticion.with(user(rol.name().toLowerCase() + "@prueba.pe").roles(rol.name()));
        }
        int obtenido = mvc.perform(peticion).andReturn().getResponse().getStatus();
        MATRIZ.computeIfAbsent(metodo + " " + ruta, k -> new TreeMap<>()).put(rol, obtenido);
        assertThat(obtenido).as("%s %s como %s", metodo, ruta, rol).isEqualTo(esperado);
    }

    /** Evidencia: la matriz obtenida en formato Markdown, lista para pegar en el informe. */
    @AfterAll
    static void escribirMatriz() throws IOException {
        List<String> lineas = new ArrayList<>();
        lineas.add("| Endpoint | Invitado | Paciente | Odontólogo |");
        lineas.add("|---|---|---|---|");
        MATRIZ.forEach((ruta, porRol) -> lineas.add("| `" + ruta + "` | " + celda(porRol.get(Rol.INVITADO)) + " | "
                + celda(porRol.get(Rol.PACIENTE)) + " | " + celda(porRol.get(Rol.ODONTOLOGO)) + " |"));
        Path archivo = Path.of("target", "seguridad", "matriz-control-acceso.md");
        Files.createDirectories(archivo.getParent());
        Files.write(archivo, lineas, StandardCharsets.UTF_8);
    }

    private static String celda(Integer codigo) {
        if (codigo == null) return "-";
        return switch (codigo) {
            case OK -> "Permitido (200)";
            case SIN_SESION -> "Sin sesión (401)";
            case PROHIBIDO -> "Prohibido (403)";
            default -> String.valueOf(codigo);
        };
    }

    // ------------------------------------------------------------------

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, RespuestasSeguridad.class, MatrizControlAccesoTest.RutasDePrueba.class})
    static class Contexto {

        @Bean
        UsuarioRepository usuarioRepository() {
            return mock(UsuarioRepository.class);
        }

        @Bean
        UsuarioDetailsService usuarioDetailsService(UsuarioRepository usuarios) {
            return new UsuarioDetailsService(usuarios);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(UsuarioRepository usuarios) {
            return new JwtAuthenticationFilter(mock(JwtService.class), usuarios);
        }

        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new CorsConfig().corsConfigurationSource("http://localhost:5173");
        }
    }

    /** Responde 200 a todo: si la petición llega aquí, la seguridad la dejó pasar. */
    @RestController
    static class RutasDePrueba {
        @RequestMapping("/**")
        String ok() {
            return "ok";
        }
    }
}

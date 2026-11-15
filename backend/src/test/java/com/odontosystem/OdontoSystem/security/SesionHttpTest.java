package com.odontosystem.OdontoSystem.security;

import com.odontosystem.OdontoSystem.config.CorsConfig;
import com.odontosystem.OdontoSystem.config.SecurityConfig;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-03 (plan de pruebas): vencimiento del token de acceso, probado por HTTP con la seguridad REAL
 * (SecurityConfig + JwtAuthenticationFilter + JwtService). La rotación y el reuso del refresh token
 * los cubre TokenRefrescoServiceTest; aquí se prueba lo que ve el cliente: 200 con un token vigente
 * y 401 en JSON (nunca 500) con uno vencido, alterado o firmado con otra clave.
 */
@SpringJUnitWebConfig(SesionHttpTest.Contexto.class)
class SesionHttpTest {

    static final String SECRETO = "clave-de-prueba-de-al-menos-32-caracteres-para-hs256";
    static final String CORREO = "lucia@correo.pe";
    static final String RUTA_PRIVADA = "/api/usuarios/yo";

    @Autowired
    private WebApplicationContext contexto;
    private MockMvc mvc;
    private UsuarioPrincipal lucia;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
        lucia = new UsuarioPrincipal(contexto.getBean(Usuario.class));
    }

    @Test
    @DisplayName("S-03: con un token vigente (15 min) la ruta privada responde 200")
    void tokenVigente() throws Exception {
        conToken(jwt(SECRETO, 15).generateAccessToken(lucia)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("S-03: con un token vencido responde 401 en JSON para que el frontend renueve con /api/auth/refresh")
    void tokenVencido() throws Exception {
        String vencido = jwt(SECRETO, -1).generateAccessToken(lucia); // venció hace un minuto
        esperar401(conToken(vencido));
    }

    @Test
    @DisplayName("S-03: con un token alterado (rol cambiado a ODONTOLOGO) responde 401, no 500")
    void tokenAlterado() throws Exception {
        String[] partes = jwt(SECRETO, 15).generateAccessToken(lucia).split("\\.");
        String payloadFalso = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + CORREO + "\",\"rol\":\"ODONTOLOGO\"}").getBytes());
        esperar401(conToken(partes[0] + "." + payloadFalso + "." + partes[2]));
    }

    @Test
    @DisplayName("S-03: con un token firmado con otra clave responde 401")
    void otraClave() throws Exception {
        String falso = jwt("otra-clave-distinta-de-al-menos-32-caracteres-xx", 15).generateAccessToken(lucia);
        esperar401(conToken(falso));
    }

    @Test
    @DisplayName("S-03: con basura en lugar de token responde 401")
    void tokenBasura() throws Exception {
        esperar401(conToken("esto-no-es-un-jwt"));
    }

    private ResultActions conToken(String token) throws Exception {
        return mvc.perform(get(RUTA_PRIVADA).header("Authorization", "Bearer " + token));
    }

    private static void esperar401(ResultActions r) throws Exception {
        r.andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", startsWith("application/json")))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.mensaje", containsString("renueva")));
    }

    static JwtService jwt(String secreto, long minutos) {
        JwtService s = new JwtService();
        ReflectionTestUtils.setField(s, "secretKey", secreto);
        ReflectionTestUtils.setField(s, "accessTokenMinutes", minutos);
        ReflectionTestUtils.setField(s, "refreshTokenDias", 30L);
        return s;
    }

    // ------------------------------------------------------------------

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, RespuestasSeguridad.class, MatrizControlAccesoTest.RutasDePrueba.class})
    static class Contexto {

        @Bean
        Usuario lucia() {
            return Usuario.builder().id(UUID.randomUUID()).correo(CORREO).rol(RolUsuario.PACIENTE).build();
        }

        @Bean
        UsuarioRepository usuarioRepository(Usuario lucia) {
            UsuarioRepository repo = mock(UsuarioRepository.class);
            when(repo.findByCorreo(CORREO)).thenReturn(Optional.of(lucia));
            return repo;
        }

        @Bean
        UsuarioDetailsService usuarioDetailsService(UsuarioRepository usuarios) {
            return new UsuarioDetailsService(usuarios);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(UsuarioRepository usuarios) {
            return new JwtAuthenticationFilter(jwt(SECRETO, 15), usuarios);
        }

        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new CorsConfig().corsConfigurationSource("http://localhost:5173");
        }
    }
}

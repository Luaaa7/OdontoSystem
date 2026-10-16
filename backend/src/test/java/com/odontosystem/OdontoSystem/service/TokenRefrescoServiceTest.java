package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.TokenRefresco;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.TokenRefrescoRepository;
import com.odontosystem.OdontoSystem.security.JwtService;
import com.odontosystem.OdontoSystem.security.TokenInvalidoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RNF01: refresh tokens opacos, guardados como hash, que rotan en cada uso y detectan la reutilización. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenRefrescoServiceTest {

    @Mock private TokenRefrescoRepository repositorio;
    @Mock private RevocacionSesionesService revocacion;

    private JwtService jwt;
    private TokenRefrescoService servicio;
    private MockHttpServletRequest http;
    private Usuario lucia;

    @BeforeEach
    void setUp() {
        jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "refreshTokenDias", 30L);
        servicio = new TokenRefrescoService(repositorio, jwt, revocacion);

        http = new MockHttpServletRequest();
        http.setRemoteAddr("190.12.3.4");
        http.addHeader("User-Agent", "OdontoSystem-Android");

        lucia = Usuario.builder().id(UUID.randomUUID()).correo("lucia@correo.pe").rol(RolUsuario.PACIENTE).build();
        when(repositorio.save(any(TokenRefresco.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("Al iniciar sesión se entrega el token y en la BD solo queda su hash, con vencimiento a 30 días, IP y dispositivo")
    void crearParaUsuario() {
        String plano = servicio.crearParaUsuario(lucia, http);

        ArgumentCaptor<TokenRefresco> guardado = ArgumentCaptor.forClass(TokenRefresco.class);
        verify(repositorio).save(guardado.capture());
        TokenRefresco t = guardado.getValue();
        assertThat(t.getTokenHash()).isEqualTo(jwt.hashearToken(plano)).isNotEqualTo(plano);
        assertThat(t.getUsuario()).isSameAs(lucia);
        assertThat(t.getFechaExpiracion()).isCloseTo(OffsetDateTime.now().plusDays(30), within(1, java.time.temporal.ChronoUnit.MINUTES));
        assertThat(t.getIpOrigen()).isEqualTo("190.12.3.4");
        assertThat(t.getUserAgent()).isEqualTo("OdontoSystem-Android");
        assertThat(t.isRevocado()).isFalse();
    }

    @Test
    @DisplayName("Refrescar rota el token: el viejo queda revocado y apunta al nuevo, que es distinto")
    void rotar() {
        String viejoPlano = jwt.generarRefreshTokenPlano();
        TokenRefresco viejo = token(viejoPlano, false, OffsetDateTime.now().plusDays(10));
        when(repositorio.findByTokenHash(jwt.hashearToken(viejoPlano))).thenReturn(Optional.of(viejo));

        TokenRefrescoService.ResultadoRotacion r = servicio.rotar(viejoPlano, http);

        assertThat(r.usuario()).isSameAs(lucia);
        assertThat(r.refreshTokenPlano()).isNotEqualTo(viejoPlano);
        assertThat(viejo.isRevocado()).isTrue();
        assertThat(viejo.getFechaRevocacion()).isNotNull();
        assertThat(viejo.getReemplazadoPor()).isNotNull();
        assertThat(viejo.getReemplazadoPor().getTokenHash()).isEqualTo(jwt.hashearToken(r.refreshTokenPlano()));

        ArgumentCaptor<TokenRefresco> guardados = ArgumentCaptor.forClass(TokenRefresco.class);
        verify(repositorio, times(2)).save(guardados.capture());
        List<TokenRefresco> lista = guardados.getAllValues();
        assertThat(lista).contains(viejo, viejo.getReemplazadoPor());
        verify(revocacion, never()).revocarTodasLasSesionesDelUsuario(any());
    }

    @Test
    @DisplayName("Un token que no existe se rechaza")
    void tokenDesconocido() {
        when(repositorio.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.rotar("inventado", http))
                .isInstanceOf(TokenInvalidoException.class)
                .hasMessageContaining("no reconocido");
    }

    @Test
    @DisplayName("Reusar un token ya rotado (posible robo) cierra TODAS las sesiones del usuario")
    void reutilizacion() {
        String plano = jwt.generarRefreshTokenPlano();
        when(repositorio.findByTokenHash(jwt.hashearToken(plano)))
                .thenReturn(Optional.of(token(plano, true, OffsetDateTime.now().plusDays(10))));

        assertThatThrownBy(() -> servicio.rotar(plano, http))
                .isInstanceOf(TokenInvalidoException.class)
                .hasMessageContaining("reutilizado");
        verify(revocacion).revocarTodasLasSesionesDelUsuario(lucia.getId());
        verify(repositorio, never()).save(any());
    }

    @Test
    @DisplayName("Un token vencido se rechaza sin crear uno nuevo")
    void tokenVencido() {
        String plano = jwt.generarRefreshTokenPlano();
        when(repositorio.findByTokenHash(jwt.hashearToken(plano)))
                .thenReturn(Optional.of(token(plano, false, OffsetDateTime.now().minusMinutes(1))));

        assertThatThrownBy(() -> servicio.rotar(plano, http))
                .isInstanceOf(TokenInvalidoException.class)
                .hasMessageContaining("expirado");
        verify(repositorio, never()).save(any());
        verify(revocacion, never()).revocarTodasLasSesionesDelUsuario(any());
    }

    private TokenRefresco token(String plano, boolean revocado, OffsetDateTime vence) {
        return TokenRefresco.builder()
                .usuario(lucia)
                .tokenHash(jwt.hashearToken(plano))
                .fechaExpiracion(vence)
                .revocado(revocado)
                .build();
    }
}

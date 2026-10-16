package com.odontosystem.OdontoSystem.security;

import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Usuario;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RNF01: el token de acceso solo vale si lo firmó el servidor, no fue alterado y no venció. */
class JwtServiceTest {

    private static final String SECRETO = "clave-de-prueba-de-al-menos-32-caracteres-para-hs256";

    private JwtService jwt;
    private UsuarioPrincipal lucia;

    @BeforeEach
    void setUp() {
        jwt = servicio(SECRETO, 15);
        lucia = principal("lucia@correo.pe", RolUsuario.PACIENTE);
    }

    @Test
    @DisplayName("El token lleva el correo, el rol y el id del usuario, y es válido para ese usuario")
    void generaTokenValido() {
        String token = jwt.generateAccessToken(lucia);

        assertThat(jwt.extractCorreo(token)).isEqualTo("lucia@correo.pe");
        assertThat(jwt.<String>extractClaim(token, c -> c.get("rol", String.class))).isEqualTo("PACIENTE");
        assertThat(jwt.<String>extractClaim(token, c -> c.get("usuarioId", String.class)))
                .isEqualTo(lucia.getUsuario().getId().toString());
        assertThat(jwt.isTokenValid(token, lucia)).isTrue();
    }

    @Test
    @DisplayName("El token de un usuario no sirve para otro usuario")
    void noSirveParaOtroUsuario() {
        String token = jwt.generateAccessToken(lucia);
        UsuarioPrincipal ana = principal("ana@correo.pe", RolUsuario.ODONTOLOGO);

        assertThat(jwt.isTokenValid(token, ana)).isFalse();
    }

    @Test
    @DisplayName("Un token vencido se rechaza")
    void tokenVencido() {
        JwtService vencido = servicio(SECRETO, -1); // expiró hace un minuto
        String token = vencido.generateAccessToken(lucia);

        assertThatThrownBy(() -> jwt.isTokenValid(token, lucia)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("Un token firmado con otra clave se rechaza")
    void otraClave() {
        String falsificado = servicio("otra-clave-distinta-de-al-menos-32-caracteres-xx", 15).generateAccessToken(lucia);

        assertThatThrownBy(() -> jwt.extractCorreo(falsificado)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Un token alterado (por ejemplo, para cambiar el rol) se rechaza")
    void tokenAlterado() {
        String token = jwt.generateAccessToken(lucia);
        String[] partes = token.split("\\.");
        String payloadFalso = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"lucia@correo.pe\",\"rol\":\"ODONTOLOGO\"}".getBytes());
        String alterado = partes[0] + "." + payloadFalso + "." + partes[2];

        assertThatThrownBy(() -> jwt.extractCorreo(alterado)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("El refresh token es aleatorio (256 bits) y en la BD solo se guarda su hash SHA-256")
    void refreshTokenYHash() {
        String a = jwt.generarRefreshTokenPlano();
        String b = jwt.generarRefreshTokenPlano();

        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+").isNotEqualTo(b);
        assertThat(jwt.hashearToken(a)).hasSize(64).matches("[0-9a-f]+")
                .isEqualTo(jwt.hashearToken(a))
                .isNotEqualTo(jwt.hashearToken(b))
                .isNotEqualTo(a);
        assertThat(jwt.getRefreshTokenDias()).isEqualTo(30);
    }

    private static JwtService servicio(String secreto, long minutos) {
        JwtService s = new JwtService();
        ReflectionTestUtils.setField(s, "secretKey", secreto);
        ReflectionTestUtils.setField(s, "accessTokenMinutes", minutos);
        ReflectionTestUtils.setField(s, "refreshTokenDias", 30L);
        return s;
    }

    private static UsuarioPrincipal principal(String correo, RolUsuario rol) {
        return new UsuarioPrincipal(Usuario.builder().id(UUID.randomUUID()).correo(correo).rol(rol).build());
    }
}

package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.entity.TokenRefresco;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.TokenRefrescoRepository;
import com.odontosystem.OdontoSystem.security.JwtService;
import com.odontosystem.OdontoSystem.security.TokenInvalidoException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class TokenRefrescoService {

    private final TokenRefrescoRepository tokenRefrescoRepository;
    private final JwtService jwtService;
    private final RevocacionSesionesService revocacionSesionesService;

    @Transactional
    public String crearParaUsuario(Usuario usuario, HttpServletRequest request) {
        String tokenPlano = jwtService.generarRefreshTokenPlano();

        TokenRefresco token = TokenRefresco.builder()
                .usuario(usuario)
                .tokenHash(jwtService.hashearToken(tokenPlano))
                .fechaExpiracion(OffsetDateTime.now().plusDays(jwtService.getRefreshTokenDias()))
                .ipOrigen(request.getRemoteAddr())
                .userAgent(request.getHeader("User-Agent"))
                .build();

        tokenRefrescoRepository.save(token);
        return tokenPlano;
    }

    @Transactional
    public ResultadoRotacion rotar(String tokenPlanoEntrante, HttpServletRequest request) {
        String hash = jwtService.hashearToken(tokenPlanoEntrante);

        TokenRefresco existente = tokenRefrescoRepository.findByTokenHash(hash)
                .orElseThrow(() -> new TokenInvalidoException("Refresh token no reconocido"));

        if (existente.isRevocado()) {
            // Llamada a OTRO bean -> sí pasa por el proxy de Spring -> REQUIRES_NEW funciona de verdad
            revocacionSesionesService.revocarTodasLasSesionesDelUsuario(existente.getUsuario().getId());
            throw new TokenInvalidoException("Refresh token reutilizado — todas las sesiones fueron revocadas");
        }

        if (existente.getFechaExpiracion().isBefore(OffsetDateTime.now())) {
            throw new TokenInvalidoException("Refresh token expirado");
        }

        Usuario usuario = existente.getUsuario();

        existente.setRevocado(true);
        existente.setFechaRevocacion(OffsetDateTime.now());

        String nuevoTokenPlano = jwtService.generarRefreshTokenPlano();
        TokenRefresco nuevo = TokenRefresco.builder()
                .usuario(usuario)
                .tokenHash(jwtService.hashearToken(nuevoTokenPlano))
                .fechaExpiracion(OffsetDateTime.now().plusDays(jwtService.getRefreshTokenDias()))
                .ipOrigen(request.getRemoteAddr())
                .userAgent(request.getHeader("User-Agent"))
                .build();

        existente.setReemplazadoPor(nuevo);
        tokenRefrescoRepository.save(nuevo);
        tokenRefrescoRepository.save(existente);

        return new ResultadoRotacion(usuario, nuevoTokenPlano);
    }

    public record ResultadoRotacion(Usuario usuario, String refreshTokenPlano) {}
}
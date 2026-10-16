package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.repository.TokenRefrescoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RevocacionSesionesServiceTest {

    @Test
    @DisplayName("Cerrar todas las sesiones revoca todos los refresh tokens activos del usuario")
    void revocaTodas() {
        TokenRefrescoRepository repositorio = mock(TokenRefrescoRepository.class);
        UUID usuario = UUID.randomUUID();

        new RevocacionSesionesService(repositorio).revocarTodasLasSesionesDelUsuario(usuario);

        verify(repositorio).revocarTodosPorUsuario(eq(usuario), any(OffsetDateTime.class));
    }
}

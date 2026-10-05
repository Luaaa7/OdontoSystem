package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.repository.TokenRefrescoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RevocacionSesionesService {

    private final TokenRefrescoRepository tokenRefrescoRepository;

    /**
     * Se ejecuta en su PROPIA transacción, independiente de quien la llame.
     * Confirma (commit) de inmediato, sin importar si el llamador luego hace rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revocarTodasLasSesionesDelUsuario(UUID usuarioId) {
        tokenRefrescoRepository.revocarTodosPorUsuario(usuarioId, OffsetDateTime.now());
    }
}
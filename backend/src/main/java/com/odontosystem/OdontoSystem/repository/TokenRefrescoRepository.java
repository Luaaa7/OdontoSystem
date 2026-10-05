package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.TokenRefresco;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TokenRefrescoRepository extends JpaRepository<TokenRefresco, UUID> {

    Optional<TokenRefresco> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
        UPDATE TokenRefresco t
        SET t.revocado = true, t.fechaRevocacion = :ahora
        WHERE t.usuario.id = :usuarioId AND t.revocado = false
        """)
    void revocarTodosPorUsuario(@Param("usuarioId") UUID usuarioId, @Param("ahora") OffsetDateTime ahora);
}
package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.NotificacionApp;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificacionAppRepository extends JpaRepository<NotificacionApp, UUID> {

    /** Las más recientes primero. */
    @Query("select n from NotificacionApp n where n.usuarioId = :usuarioId order by n.creadoEn desc")
    List<NotificacionApp> recientes(@Param("usuarioId") UUID usuarioId, Pageable pagina);

    long countByUsuarioIdAndLeidaEnIsNull(UUID usuarioId);

    Optional<NotificacionApp> findByIdAndUsuarioId(UUID id, UUID usuarioId);

    @Modifying
    @Query("update NotificacionApp n set n.leidaEn = :ahora where n.usuarioId = :usuarioId and n.leidaEn is null")
    int marcarTodasLeidas(@Param("usuarioId") UUID usuarioId, @Param("ahora") OffsetDateTime ahora);
}

package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.EstadoChat;
import com.odontosystem.OdontoSystem.entity.SesionChat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SesionChatRepository extends JpaRepository<SesionChat, UUID> {

    /** La conversación más reciente del usuario en el estado indicado (normalmente ACTIVA). */
    Optional<SesionChat> findFirstByUsuarioIdAndEstadoOrderByActualizadoEnDesc(UUID usuarioId, EstadoChat estado);
}

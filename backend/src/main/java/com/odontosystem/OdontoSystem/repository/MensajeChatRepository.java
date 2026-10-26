package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.MensajeChat;
import com.odontosystem.OdontoSystem.entity.TipoEmisorMensaje;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MensajeChatRepository extends JpaRepository<MensajeChat, UUID> {

    /** Todos los mensajes de una conversación, del más antiguo al más reciente. */
    List<MensajeChat> findBySesionIdOrderByCreadoEnAsc(UUID sesionId);

    /** El último mensaje de un emisor en la conversación (para la memoria de contexto del bot). */
    Optional<MensajeChat> findFirstBySesionIdAndTipoEmisorOrderByCreadoEnDesc(UUID sesionId, TipoEmisorMensaje tipoEmisor);
}

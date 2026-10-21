package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.EstadoReembolso;
import com.odontosystem.OdontoSystem.entity.Reembolso;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReembolsoRepository extends JpaRepository<Reembolso, UUID> {

    Optional<Reembolso> findByPago_IdAndClaveIdempotencia(UUID pagoId, String claveIdempotencia);

    List<Reembolso> findByPago_Cita_IdOrderBySolicitadoEnDesc(UUID citaId);

    /** Devoluciones en ese estado (reintento de las SOLICITADO). */
    List<Reembolso> findByEstado(EstadoReembolso estado);
}

package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.Pago;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PagoRepository extends JpaRepository<Pago, UUID> {

    /** El pago de la cita en ese estado (la BD permite un solo pago VERIFICADO por cita). */
    Optional<Pago> findFirstByCita_IdAndEstado(UUID citaId, EstadoPago estado);
}

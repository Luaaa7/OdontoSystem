package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.Pago;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PagoRepository extends JpaRepository<Pago, UUID> {

    /** El pago de la cita en ese estado (la BD permite un solo pago VERIFICADO por cita). */
    Optional<Pago> findFirstByCita_IdAndEstado(UUID citaId, EstadoPago estado);

    /** Reprogramación: los pagos de la cita original pasan a la cita nueva (cita_id no se edita desde la entidad). */
    @Modifying
    @Query(value = "UPDATE pagos SET cita_id = :nueva WHERE cita_id = :origen", nativeQuery = true)
    int moverACita(@Param("origen") UUID citaOrigenId, @Param("nueva") UUID citaNuevaId);
}

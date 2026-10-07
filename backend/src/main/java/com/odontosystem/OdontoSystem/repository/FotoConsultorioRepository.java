package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.FotoConsultorio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FotoConsultorioRepository extends JpaRepository<FotoConsultorio, UUID> {

    List<FotoConsultorio> findByOdontologoIdOrderByOrdenAsc(UUID odontologoId);

    /** Busca la foto solo si pertenece al odontólogo: así nadie borra fotos ajenas. */
    Optional<FotoConsultorio> findByIdAndOdontologoId(UUID id, UUID odontologoId);
}

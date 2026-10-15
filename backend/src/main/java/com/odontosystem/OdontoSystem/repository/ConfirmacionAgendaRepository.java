package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.ConfirmacionAgenda;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ConfirmacionAgendaRepository extends JpaRepository<ConfirmacionAgenda, UUID> {

    Optional<ConfirmacionAgenda> findByOdontologoIdAndFecha(UUID odontologoId, LocalDate fecha);

    boolean existsByOdontologoIdAndFechaAndAtiendeTrue(UUID odontologoId, LocalDate fecha);
}

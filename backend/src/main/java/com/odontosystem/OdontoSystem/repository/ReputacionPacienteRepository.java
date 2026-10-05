package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.ReputacionPaciente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReputacionPacienteRepository extends JpaRepository<ReputacionPaciente, UUID> {
}
package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.VerificacionCop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VerificacionCopRepository extends JpaRepository<VerificacionCop, Long> {
}
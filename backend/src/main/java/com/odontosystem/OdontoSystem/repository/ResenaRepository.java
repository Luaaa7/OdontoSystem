package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.Resena;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ResenaRepository extends JpaRepository<Resena, Long> {

    boolean existsByCita_Id(UUID citaId);
}
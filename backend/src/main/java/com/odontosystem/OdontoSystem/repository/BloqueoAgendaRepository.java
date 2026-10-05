package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.BloqueoAgenda;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BloqueoAgendaRepository extends JpaRepository<BloqueoAgenda, UUID> {
    List<BloqueoAgenda> findByOdontologo_UsuarioIdOrderByInicioAsc(UUID odontologoId);
}
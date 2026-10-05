package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PerfilOdontologoRepository extends JpaRepository<PerfilOdontologo, UUID> {
    boolean existsByNumeroColegiatura(String numeroColegiatura);
    Optional<PerfilOdontologo> findByNumeroColegiatura(String numeroColegiatura);
}
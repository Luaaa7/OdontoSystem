package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.Servicio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServicioRepository extends JpaRepository<Servicio, UUID> {
    List<Servicio> findByOdontologo_UsuarioIdAndEstaActivoTrue(UUID odontologoId);

    List<Servicio> findByOdontologo_UsuarioIdInAndEstaActivoTrue(java.util.Collection<UUID> odontologoIds);

    List<Servicio> findByOdontologo_UsuarioIdOrderByCreadoEnAsc(UUID odontologoId);

    java.util.Optional<Servicio> findByIdAndOdontologo_UsuarioId(UUID id, UUID odontologoId);
}
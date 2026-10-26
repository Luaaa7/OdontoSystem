package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.HorarioAtencion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HorarioAtencionRepository extends JpaRepository<HorarioAtencion, UUID> {
    List<HorarioAtencion> findByOdontologo_UsuarioIdOrderByDiaSemanaAscHoraInicioAsc(UUID odontologoId);

    @org.springframework.data.jpa.repository.Modifying
    void deleteByOdontologo_UsuarioId(UUID odontologoId);
}
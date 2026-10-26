package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.Especialidad;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EspecialidadRepository extends JpaRepository<Especialidad, Short> {
    List<Especialidad> findAllByOrderByNombreAsc();
}

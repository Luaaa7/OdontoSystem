package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.CategoriaServicio;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoriaServicioRepository extends JpaRepository<CategoriaServicio, Short> {
    java.util.List<CategoriaServicio> findByEstaActivoTrueOrderByIdAsc();
}
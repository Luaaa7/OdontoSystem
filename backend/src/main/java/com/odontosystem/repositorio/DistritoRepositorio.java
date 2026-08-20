package com.odontosystem.repositorio;

import com.odontosystem.entidad.Distrito;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DistritoRepositorio extends JpaRepository<Distrito, Long> {
    Optional<Distrito> findByNombreIgnoreCase(String nombre);
}

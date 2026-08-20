package com.odontosystem.repositorio;

import com.odontosystem.entidad.Odontologo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OdontologoRepositorio extends JpaRepository<Odontologo, Long> {

    // Usado tanto por la busqueda web como por el intent BUSCAR_ODONTOLOGO del chatbot
    List<Odontologo> findByDistrito_NombreIgnoreCaseAndPerfilCompletoTrue(String distrito);

    List<Odontologo> findByPerfilCompletoTrue();
}

package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.VerificacionCop;
import org.springframework.data.jpa.repository.JpaRepository;


public interface VerificacionCopRepository extends JpaRepository<VerificacionCop, Long> {

    long countByOdontologo_UsuarioIdAndResultado(java.util.UUID odontologoId,
                                                 com.odontosystem.OdontoSystem.entity.ResultadoVerificacionCop resultado);

    java.util.Optional<VerificacionCop> findFirstByOdontologo_UsuarioIdOrderByEjecutadoEnDesc(java.util.UUID odontologoId);
}
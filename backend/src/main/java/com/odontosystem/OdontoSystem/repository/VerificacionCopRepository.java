package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.VerificacionCop;
import org.springframework.data.jpa.repository.JpaRepository;


public interface VerificacionCopRepository extends JpaRepository<VerificacionCop, Long> {

    long countByOdontologo_UsuarioIdAndResultado(java.util.UUID odontologoId,
                                                 com.odontosystem.OdontoSystem.entity.ResultadoVerificacionCop resultado);

    java.util.Optional<VerificacionCop> findFirstByOdontologo_UsuarioIdOrderByEjecutadoEnDesc(java.util.UUID odontologoId);

    /** true si alguna vez se registró un intento cuyo detalle contiene ese texto (p. ej. la marca de RENIEC). */
    boolean existsByOdontologo_UsuarioIdAndDetalleContaining(java.util.UUID odontologoId, String texto);
}
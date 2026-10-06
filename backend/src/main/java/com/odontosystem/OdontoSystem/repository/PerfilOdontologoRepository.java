package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PerfilOdontologoRepository extends JpaRepository<PerfilOdontologo, UUID> {
    boolean existsByNumeroColegiatura(String numeroColegiatura);
    Optional<PerfilOdontologo> findByNumeroColegiatura(String numeroColegiatura);

    /** Perfiles activos (sin baja lógica) con el estado de verificación indicado y con distrito registrado. Lo usa el chatbot. */
    List<PerfilOdontologo> findByDesactivadoEnIsNullAndEstadoVerificacionAndDistritoConsultorioIsNotNull(
            EstadoVerificacionOdontologo estadoVerificacion);
}
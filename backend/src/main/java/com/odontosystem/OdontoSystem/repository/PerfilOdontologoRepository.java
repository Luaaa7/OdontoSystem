package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PerfilOdontologoRepository extends JpaRepository<PerfilOdontologo, UUID> {
    boolean existsByNumeroColegiatura(String numeroColegiatura);
    Optional<PerfilOdontologo> findByNumeroColegiatura(String numeroColegiatura);

    /** Perfiles activos (sin baja lógica) con el estado de verificación indicado y con distrito registrado. Lo usa el chatbot. */
    List<PerfilOdontologo> findByDesactivadoEnIsNullAndEstadoVerificacionAndDistritoConsultorioIsNotNull(
            EstadoVerificacionOdontologo estadoVerificacion);

    /** Perfiles que pueden mostrarse en el buscador: activos, con cuenta activa y el estado indicado (VERIFICADO). */
    @Query("select distinct p from PerfilOdontologo p join fetch p.usuario u left join fetch p.especialidades "
            + "where p.desactivadoEn is null and u.estaActivo = true and p.estadoVerificacion = :estado")
    List<PerfilOdontologo> buscarPublicables(@Param("estado") EstadoVerificacionOdontologo estado);
}
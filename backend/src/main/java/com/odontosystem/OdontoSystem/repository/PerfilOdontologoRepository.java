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

    /** Perfiles en ese estado de verificación (RF09: reintento de los PENDIENTE). */
    List<PerfilOdontologo> findByEstadoVerificacion(EstadoVerificacionOdontologo estadoVerificacion);

    /** Perfiles activos (sin baja lógica) con el estado de verificación indicado y con distrito registrado. Lo usa el chatbot. */
    List<PerfilOdontologo> findByDesactivadoEnIsNullAndEstadoVerificacionAndDistritoConsultorioIsNotNull(
            EstadoVerificacionOdontologo estadoVerificacion);

    /** Perfiles que pueden mostrarse en el buscador: activos, con cuenta activa y el estado indicado (VERIFICADO). */
    @Query("select distinct p from PerfilOdontologo p join fetch p.usuario u left join fetch p.especialidades "
            + "where p.desactivadoEn is null and u.estaActivo = true and p.estadoVerificacion = :estado "
            + "and (p.suspendidoHasta is null or p.suspendidoHasta < current_timestamp)")
    List<PerfilOdontologo> buscarPublicables(@Param("estado") EstadoVerificacionOdontologo estado);

    /** El perfil con su usuario en UNA consulta (findById + getUsuario() eran dos idas a la BD; DEF-05). */
    @Query("select p from PerfilOdontologo p join fetch p.usuario where p.usuarioId = :id")
    Optional<PerfilOdontologo> buscarConUsuario(@Param("id") UUID id);
}
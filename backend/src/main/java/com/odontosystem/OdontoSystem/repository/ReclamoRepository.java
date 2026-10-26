package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.EstadoReclamo;
import com.odontosystem.OdontoSystem.entity.Reclamo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReclamoRepository extends JpaRepository<Reclamo, UUID> {

    boolean existsByCita_IdAndEstadoIn(UUID citaId, Collection<EstadoReclamo> estados);

    /** Reclamos sin resolver que están asociados a una cita (para revisar si venció el plazo de respuesta). */
    @Query("select r from Reclamo r join fetch r.usuario join fetch r.cita c join fetch c.paciente "
            + "join fetch c.odontologo o join fetch o.usuario left join fetch c.servicio where r.estado in :estados")
    List<Reclamo> conCitaEnEstados(@Param("estados") Collection<EstadoReclamo> estados);

    long countByUsuario_IdAndEstadoIn(UUID usuarioId, Collection<EstadoReclamo> estados);

    @Query("select r from Reclamo r join fetch r.usuario left join fetch r.cita c left join fetch c.odontologo o "
            + "left join fetch o.usuario left join fetch c.servicio where r.id = :id")
    Optional<Reclamo> conDetalle(@Param("id") UUID id);

    @Query("select r from Reclamo r join fetch r.usuario left join fetch r.cita c left join fetch c.odontologo o "
            + "left join fetch o.usuario left join fetch c.servicio where r.usuario.id = :usuarioId "
            + "order by r.creadoEn desc")
    List<Reclamo> delUsuario(@Param("usuarioId") UUID usuarioId);

    /** Reclamos que pacientes hicieron sobre citas de este odontólogo (los que él debe atender). */
    @Query("select r from Reclamo r join fetch r.usuario u join fetch r.cita c join fetch c.odontologo o "
            + "join fetch o.usuario left join fetch c.servicio "
            + "where o.usuarioId = :odontologoId and u.id = c.paciente.id order by r.creadoEn asc")
    List<Reclamo> paraOdontologo(@Param("odontologoId") UUID odontologoId);
}

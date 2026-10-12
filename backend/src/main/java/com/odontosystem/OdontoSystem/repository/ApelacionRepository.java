package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.Apelacion;
import com.odontosystem.OdontoSystem.entity.EstadoApelacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApelacionRepository extends JpaRepository<Apelacion, UUID> {

    boolean existsByCita_Id(UUID citaId);

    Optional<Apelacion> findByCita_Id(UUID citaId);

    /** Apelaciones sobre citas de ese odontólogo, las más antiguas primero. */
    @Query("select a from Apelacion a join fetch a.cita c join fetch c.paciente join fetch c.servicio "
            + "where c.odontologo.usuarioId = :odontologoId order by a.creadoEn asc")
    List<Apelacion> delOdontologo(@Param("odontologoId") UUID odontologoId);

    /** Igual, pero solo las de un estado (por ejemplo, las PENDIENTE por resolver). */
    @Query("select a from Apelacion a join fetch a.cita c join fetch c.paciente join fetch c.servicio "
            + "where c.odontologo.usuarioId = :odontologoId and a.estado = :estado order by a.creadoEn asc")
    List<Apelacion> delOdontologoEnEstado(@Param("odontologoId") UUID odontologoId,
                                          @Param("estado") EstadoApelacion estado);

    @Query("select a from Apelacion a join fetch a.cita c join fetch c.paciente join fetch c.servicio where a.id = :id")
    Optional<Apelacion> conDetalle(@Param("id") UUID id);
}

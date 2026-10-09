package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CitaRepository extends JpaRepository<Cita, UUID> {

    /** Citas del odontólogo en esos estados que se cruzan con el rango [desde, hasta). */
    @Query("select c from Cita c where c.odontologo.usuarioId = :odontologoId and c.estado in :estados "
            + "and c.inicioProgramado < :hasta and c.finProgramado > :desde")
    List<Cita> cruzadas(@Param("odontologoId") UUID odontologoId,
                        @Param("estados") Collection<EstadoCita> estados,
                        @Param("desde") OffsetDateTime desde,
                        @Param("hasta") OffsetDateTime hasta);

    @Query("select c from Cita c join fetch c.odontologo o join fetch o.usuario join fetch c.servicio "
            + "where c.paciente.id = :pacienteId order by c.inicioProgramado desc")
    List<Cita> delPaciente(@Param("pacienteId") UUID pacienteId);

    @Query("select c from Cita c join fetch c.paciente join fetch c.servicio "
            + "where c.odontologo.usuarioId = :odontologoId and c.inicioProgramado >= :desde "
            + "and c.inicioProgramado < :hasta order by c.inicioProgramado asc")
    List<Cita> agenda(@Param("odontologoId") UUID odontologoId,
                      @Param("desde") OffsetDateTime desde,
                      @Param("hasta") OffsetDateTime hasta);

    long countByPaciente_IdAndEstado(UUID pacienteId, EstadoCita estado);

    List<Cita> findByEstadoAndCreadoEnBefore(EstadoCita estado, OffsetDateTime limite);
}

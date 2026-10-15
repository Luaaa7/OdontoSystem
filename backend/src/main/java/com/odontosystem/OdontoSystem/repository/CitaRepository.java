package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    /** Citas en ese estado que empiezan entre desde y hasta y todavía no tienen recordatorios programados. */
    @Query("select c from Cita c where c.estado = :estado and c.inicioProgramado > :desde "
            + "and c.inicioProgramado <= :hasta and not exists (select r.id from RecordatorioCita r where r.cita = c)")
    List<Cita> sinRecordatorios(@Param("estado") EstadoCita estado,
                                @Param("desde") OffsetDateTime desde,
                                @Param("hasta") OffsetDateTime hasta);

    /** La cita con paciente, odontólogo y servicio (para armar correos fuera de la transacción). */
    @Query("select c from Cita c join fetch c.paciente join fetch c.odontologo o join fetch o.usuario "
            + "join fetch c.servicio where c.id = :id")
    Optional<Cita> conDetalle(@Param("id") UUID id);

    /** Citas atendidas del paciente que todavía no tienen reseña, las más recientes primero. */
    @Query("select c from Cita c join fetch c.odontologo o join fetch o.usuario join fetch c.servicio "
            + "where c.paciente.id = :pacienteId and c.estado = com.odontosystem.OdontoSystem.entity.EstadoCita.COMPLETADA "
            + "and not exists (select r.id from Resena r where r.cita = c) order by c.inicioProgramado desc")
    List<Cita> completadasSinResena(@Param("pacienteId") UUID pacienteId);

    long countByPaciente_IdAndEstado(UUID pacienteId, EstadoCita estado);

    /** Citas en esos estados que empiezan en el rango, con el odontólogo (para el aviso diario de agenda). */
    @Query("select c from Cita c join fetch c.odontologo o join fetch o.usuario where c.estado in :estados "
            + "and c.inicioProgramado >= :desde and c.inicioProgramado < :hasta order by c.inicioProgramado asc")
    List<Cita> empiezanEntre(@Param("estados") Collection<EstadoCita> estados,
                             @Param("desde") OffsetDateTime desde,
                             @Param("hasta") OffsetDateTime hasta);

    /** Citas en ese estado que terminaron antes del límite y que nadie reportó (para cerrarlas solas). */
    @Query("select c from Cita c join fetch c.paciente where c.estado = :estado and c.finProgramado < :limite "
            + "and not exists (select r.id from ReporteAusencia r where r.cita = c)")
    List<Cita> terminadasSinReporte(@Param("estado") EstadoCita estado, @Param("limite") OffsetDateTime limite);

    List<Cita> findByEstadoAndCreadoEnBefore(EstadoCita estado, OffsetDateTime limite);
}

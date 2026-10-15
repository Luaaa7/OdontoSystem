package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.EstadoReporteAusencia;
import com.odontosystem.OdontoSystem.entity.ReporteAusencia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReporteAusenciaRepository extends JpaRepository<ReporteAusencia, UUID> {

    boolean existsByCita_Id(UUID citaId);

    boolean existsByCita_IdAndEstado(UUID citaId, EstadoReporteAusencia estado);

    /** Reportes que hizo el paciente desde esa fecha (para detectar reportes repetidos). */
    long countByCita_Paciente_IdAndCreadoEnAfter(UUID pacienteId, OffsetDateTime desde);

    /** Faltas confirmadas del odontólogo desde esa fecha. */
    long countByCita_Odontologo_UsuarioIdAndEstadoAndResueltoEnAfter(UUID odontologoId, EstadoReporteAusencia estado,
                                                                     OffsetDateTime desde);

    @Query("select r from ReporteAusencia r join fetch r.cita c join fetch c.paciente join fetch c.servicio "
            + "join fetch c.odontologo o join fetch o.usuario where r.id = :id")
    Optional<ReporteAusencia> conDetalle(@Param("id") UUID id);

    @Query("select r from ReporteAusencia r join fetch r.cita c join fetch c.paciente join fetch c.servicio "
            + "join fetch c.odontologo o join fetch o.usuario where r.estado = :estado and r.creadoEn < :limite")
    List<ReporteAusencia> enEstadoCreadosAntesDe(@Param("estado") EstadoReporteAusencia estado,
                                                 @Param("limite") OffsetDateTime limite);

    @Query("select r from ReporteAusencia r join fetch r.cita c join fetch c.paciente join fetch c.servicio "
            + "where c.odontologo.usuarioId = :odontologoId order by r.creadoEn desc")
    List<ReporteAusencia> delOdontologo(@Param("odontologoId") UUID odontologoId);
}

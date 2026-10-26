package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.HistoriaClinica;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HistoriaClinicaRepository extends JpaRepository<HistoriaClinica, Long> {

    boolean existsByCita_Id(UUID citaId);

    @Query("select h from HistoriaClinica h join fetch h.cita c join fetch c.odontologo o join fetch o.usuario "
            + "join fetch c.servicio where c.id = :citaId")
    Optional<HistoriaClinica> deCita(@Param("citaId") UUID citaId);

    /** Historias de un paciente, las más recientes primero. */
    @Query("select h from HistoriaClinica h join fetch h.cita c join fetch c.odontologo o join fetch o.usuario "
            + "join fetch c.servicio where h.pacienteId = :pacienteId order by c.inicioProgramado desc")
    List<HistoriaClinica> delPaciente(@Param("pacienteId") UUID pacienteId);

    /** Historias que escribió un odontólogo para un paciente (no ve las de otros odontólogos). */
    @Query("select h from HistoriaClinica h join fetch h.cita c join fetch c.odontologo o join fetch o.usuario "
            + "join fetch c.servicio where h.pacienteId = :pacienteId and h.creadoPorOdontologoId = :odontologoId "
            + "order by c.inicioProgramado desc")
    List<HistoriaClinica> delPacienteEscritasPor(@Param("pacienteId") UUID pacienteId,
                                                 @Param("odontologoId") UUID odontologoId);
}

package com.odontosystem.repositorio;

import com.odontosystem.entidad.Cita;
import com.odontosystem.entidad.EstadoCita;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CitaRepositorio extends JpaRepository<Cita, Long> {

    List<Cita> findByPaciente_IdOrderByFechaHoraDesc(Long pacienteId);

    List<Cita> findByOdontologo_Id(Long odontologoId);

    // Para validar si un horario ya esta ocupado (usado por DisponibilidadService)
    List<Cita> findByOdontologo_IdAndFechaHoraBetweenAndEstadoNot(
            Long odontologoId, LocalDateTime desde, LocalDateTime hasta, EstadoCita estadoExcluido);

    // Para el intent CONSULTAR_HISTORIAL del chatbot: ultima cita del paciente
    Optional<Cita> findFirstByPaciente_IdOrderByFechaHoraDesc(Long pacienteId);

    // Para "tu proxima cita agendada" en el chatbot
    Optional<Cita> findFirstByPaciente_IdAndFechaHoraAfterOrderByFechaHoraAsc(
            Long pacienteId, LocalDateTime ahora);
}

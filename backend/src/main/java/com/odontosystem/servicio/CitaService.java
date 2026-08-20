package com.odontosystem.servicio;

import com.odontosystem.entidad.Cita;
import com.odontosystem.entidad.EstadoCita;
import com.odontosystem.entidad.Odontologo;
import com.odontosystem.entidad.Usuario;
import com.odontosystem.repositorio.CitaRepositorio;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CitaService {

    private final CitaRepositorio citaRepositorio;
    private final DisponibilidadService disponibilidadService;

    /**
     * Crea una cita validando disponibilidad real justo antes de guardar.
     * @param creadaPorChatbot para tus metricas de "citas agendadas via chatbot vs web"
     */
    public Cita crearCita(Usuario paciente, Odontologo odontologo, LocalDateTime fechaHora, boolean creadaPorChatbot) {
        if (!disponibilidadService.estaDisponible(odontologo.getId(), fechaHora)) {
            throw new IllegalStateException("Ese horario ya no esta disponible");
        }
        Cita cita = new Cita();
        cita.setPaciente(paciente);
        cita.setOdontologo(odontologo);
        cita.setFechaHora(fechaHora);
        cita.setEstado(EstadoCita.CONFIRMADA);
        cita.setCreadaPorChatbot(creadaPorChatbot);
        return citaRepositorio.save(cita);
    }

    public List<Cita> listarPorPaciente(Long pacienteId) {
        return citaRepositorio.findByPaciente_IdOrderByFechaHoraDesc(pacienteId);
    }

    public List<Cita> listarPorOdontologo(Long odontologoId) {
        return citaRepositorio.findByOdontologo_Id(odontologoId);
    }

    /** Usado por el intent CONSULTAR_HISTORIAL: la ultima cita ya pasada del paciente */
    public Optional<Cita> ultimaCita(Long pacienteId) {
        return citaRepositorio.findFirstByPaciente_IdOrderByFechaHoraDesc(pacienteId);
    }

    /** Usado por el intent CONSULTAR_HISTORIAL: proxima cita agendada, si existe */
    public Optional<Cita> proximaCita(Long pacienteId) {
        return citaRepositorio.findFirstByPaciente_IdAndFechaHoraAfterOrderByFechaHoraAsc(
                pacienteId, LocalDateTime.now());
    }
}

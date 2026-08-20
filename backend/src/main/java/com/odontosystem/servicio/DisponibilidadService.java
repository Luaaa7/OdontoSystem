package com.odontosystem.servicio;

import com.odontosystem.entidad.Cita;
import com.odontosystem.entidad.EstadoCita;
import com.odontosystem.repositorio.CitaRepositorio;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DisponibilidadService {

    private final CitaRepositorio citaRepositorio;

    private static final LocalTime HORA_INICIO = LocalTime.of(9, 0);
    private static final LocalTime HORA_FIN = LocalTime.of(18, 0);
    private static final int DURACION_CITA_MINUTOS = 60;

    /**
     * Genera los horarios disponibles de un odontologo para un dia dado,
     * descartando los que ya tienen una cita activa (no cancelada).
     * Usado por el endpoint REST y por el intent AGENDAR_CITA del chatbot.
     */
    public List<LocalDateTime> horariosDisponibles(Long odontologoId, java.time.LocalDate dia) {
        LocalDateTime desde = dia.atTime(HORA_INICIO);
        LocalDateTime hasta = dia.atTime(HORA_FIN);

        List<Cita> ocupadas = citaRepositorio.findByOdontologo_IdAndFechaHoraBetweenAndEstadoNot(
                odontologoId, desde, hasta, EstadoCita.CANCELADA);

        List<LocalDateTime> horariosOcupados = ocupadas.stream().map(Cita::getFechaHora).toList();

        List<LocalDateTime> disponibles = new ArrayList<>();
        LocalDateTime cursor = desde;
        while (cursor.isBefore(hasta)) {
            if (!horariosOcupados.contains(cursor)) {
                disponibles.add(cursor);
            }
            cursor = cursor.plusMinutes(DURACION_CITA_MINUTOS);
        }
        return disponibles;
    }

    /**
     * Verifica en el momento de confirmar si el horario sigue libre
     * (evita condiciones de carrera cuando dos pacientes eligen el mismo slot).
     */
    public boolean estaDisponible(Long odontologoId, LocalDateTime fechaHora) {
        List<Cita> ocupadas = citaRepositorio.findByOdontologo_IdAndFechaHoraBetweenAndEstadoNot(
                odontologoId, fechaHora, fechaHora.plusMinutes(1), EstadoCita.CANCELADA);
        return ocupadas.isEmpty();
    }
}

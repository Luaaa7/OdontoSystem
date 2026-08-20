package com.odontosystem.controlador;

import com.odontosystem.entidad.Cita;
import com.odontosystem.servicio.CitaService;
import com.odontosystem.servicio.DisponibilidadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/citas")
@RequiredArgsConstructor
public class CitaController {

    private final CitaService citaService;
    private final DisponibilidadService disponibilidadService;

    @GetMapping("/paciente/{pacienteId}")
    public List<Cita> porPaciente(@PathVariable Long pacienteId) {
        return citaService.listarPorPaciente(pacienteId);
    }

    @GetMapping("/odontologo/{odontologoId}")
    public List<Cita> porOdontologo(@PathVariable Long odontologoId) {
        return citaService.listarPorOdontologo(odontologoId);
    }

    // GET /api/citas/disponibilidad?odontologoId=1&dia=2026-09-01
    @GetMapping("/disponibilidad")
    public List<LocalDateTime> disponibilidad(@RequestParam Long odontologoId, @RequestParam String dia) {
        return disponibilidadService.horariosDisponibles(odontologoId, LocalDate.parse(dia));
    }
}

package com.odontosystem.OdontoSystem.pacientes;

import com.odontosystem.OdontoSystem.pacientes.PacienteService.PacienteDetalle;
import com.odontosystem.OdontoSystem.pacientes.PacienteService.PacienteResumen;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Pacientes del odontólogo autenticado (rol ODONTOLOGO, ver SecurityConfig). */
@Tag(name = "Mis pacientes", description = "Pacientes que tuvieron al menos una cita con el odontólogo")
@RestController
@RequiredArgsConstructor
public class PacientesController {

    private final PacienteService pacienteService;

    @Operation(summary = "Mis pacientes (odontólogo)",
            description = "Todos los pacientes con al menos una cita conmigo, en cualquier fecha: totales, última atención "
                    + "y próxima cita. Primero los que tienen una cita próxima.")
    @GetMapping("/api/odontologos/yo/pacientes")
    public List<PacienteResumen> misPacientes(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return pacienteService.deOdontologo(principal.getUsuario().getId());
    }

    @Operation(summary = "Detalle de un paciente (odontólogo)",
            description = "Solo si el paciente tuvo al menos una cita conmigo; si no, 404. alergias y antecedentes "
                    + "aún no se registran: llegan en null (mostrar \"Sin datos\").")
    @GetMapping("/api/pacientes/{pacienteId}")
    public PacienteDetalle detalle(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID pacienteId) {
        return pacienteService.detalle(principal.getUsuario().getId(), pacienteId);
    }
}

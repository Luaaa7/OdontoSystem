package com.odontosystem.OdontoSystem.historias;

import com.odontosystem.OdontoSystem.historias.HistoriaDtos.HistoriaRequest;
import com.odontosystem.OdontoSystem.historias.HistoriaDtos.HistoriaResponse;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Historias clínicas", description = "Diagnóstico y tratamiento de cada cita atendida, cifrados en la BD (Ley N.° 29733)")
@RestController
@RequiredArgsConstructor
public class HistoriasController {

    private final HistoriaClinicaService historiaService;

    @Operation(summary = "Registrar la historia clínica de una cita atendida (odontólogo)",
            description = "Solo el odontólogo de la cita, cuando está COMPLETADA, una vez. No se puede editar después.")
    @PostMapping("/api/citas/{citaId}/historia")
    @ResponseStatus(HttpStatus.CREATED)
    public HistoriaResponse registrar(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId,
                                      @Valid @RequestBody HistoriaRequest request) {
        return historiaService.registrar(principal.getUsuario().getId(), citaId, request);
    }

    @Operation(summary = "Historia clínica de una cita (su paciente o el odontólogo que la escribió)")
    @GetMapping("/api/citas/{citaId}/historia")
    public HistoriaResponse deCita(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId) {
        return historiaService.deCita(principal.getUsuario().getId(), citaId);
    }

    @Operation(summary = "Mi historial clínico (paciente)", description = "Todas mis historias, la más reciente primero.")
    @GetMapping("/api/historias/mias")
    public List<HistoriaResponse> mias(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return historiaService.delPaciente(principal.getUsuario().getId());
    }

    @Operation(summary = "Historias que escribí para un paciente (odontólogo)")
    @GetMapping("/api/odontologos/yo/historias")
    public List<HistoriaResponse> escritasPorMi(@AuthenticationPrincipal UsuarioPrincipal principal,
                                                @RequestParam(name = "paciente_id") UUID pacienteId) {
        return historiaService.escritasPor(principal.getUsuario().getId(), pacienteId);
    }
}

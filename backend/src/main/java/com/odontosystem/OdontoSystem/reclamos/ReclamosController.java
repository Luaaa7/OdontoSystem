package com.odontosystem.OdontoSystem.reclamos;

import com.odontosystem.OdontoSystem.entity.EstadoReclamo;
import com.odontosystem.OdontoSystem.reclamos.ReclamoDtos.ReclamoRequest;
import com.odontosystem.OdontoSystem.reclamos.ReclamoDtos.ReclamoResponse;
import com.odontosystem.OdontoSystem.reclamos.ReclamoDtos.RespuestaRequest;
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

@Tag(name = "Reclamos", description = "Libro de reclamaciones: consultas, incidencias y reclamos con seguimiento")
@RestController
@RequiredArgsConstructor
public class ReclamosController {

    private final ReclamoService reclamoService;

    @Operation(summary = "Registrar una consulta, incidencia o reclamo",
            description = "cita_id es opcional y debe ser una cita propia. Si un paciente reclama sobre una cita, lo "
                    + "atiende el odontólogo de esa cita; lo demás, el equipo de soporte. Se envía una constancia por "
                    + "correo con el código y la fecha límite de respuesta (15 días hábiles).")
    @PostMapping("/api/reclamos")
    @ResponseStatus(HttpStatus.CREATED)
    public ReclamoResponse registrar(@AuthenticationPrincipal UsuarioPrincipal principal,
                                     @Valid @RequestBody ReclamoRequest request) {
        return reclamoService.registrar(principal.getUsuario().getId(), request);
    }

    @Operation(summary = "Mis reclamos, el más reciente primero")
    @GetMapping("/api/reclamos/mios")
    public List<ReclamoResponse> mios(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return reclamoService.mios(principal.getUsuario().getId());
    }

    @Operation(summary = "Detalle de un reclamo (quien lo registró o el odontólogo que lo atiende)")
    @GetMapping("/api/reclamos/{id}")
    public ReclamoResponse detalle(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID id) {
        return reclamoService.detalle(principal.getUsuario().getId(), id);
    }

    @Operation(summary = "Cerrar mi reclamo", description = "Cuando ya estoy conforme o ya no lo necesito.")
    @PatchMapping("/api/reclamos/{id}/cerrar")
    public ReclamoResponse cerrar(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID id) {
        return reclamoService.cerrar(principal.getUsuario().getId(), id);
    }

    @Operation(summary = "Reclamos de mis pacientes (odontólogo)",
            description = "Filtro opcional estado = ABIERTO | EN_PROCESO | RESUELTO | CERRADO. Los más antiguos primero.")
    @GetMapping("/api/odontologos/yo/reclamos")
    public List<ReclamoResponse> bandeja(@AuthenticationPrincipal UsuarioPrincipal principal,
                                         @RequestParam(required = false) EstadoReclamo estado) {
        return reclamoService.paraOdontologo(principal.getUsuario().getId(), estado);
    }

    @Operation(summary = "Responder un reclamo (odontólogo)",
            description = "Con resuelto=true pasa a RESUELTO; si no, queda EN_PROCESO. El paciente recibe la respuesta por correo.")
    @PatchMapping("/api/odontologos/yo/reclamos/{id}")
    public ReclamoResponse responder(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID id,
                                     @Valid @RequestBody RespuestaRequest request) {
        return reclamoService.responder(principal.getUsuario().getId(), id, request);
    }
}

package com.odontosystem.OdontoSystem.resenas;

import com.odontosystem.OdontoSystem.resenas.ResenaDtos.CitaPorResenar;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenaRequest;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenaResponse;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenasOdontologoResponse;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Reseñas", description = "Calificación de citas atendidas (1 a 5 estrellas) y reseñas públicas del odontólogo")
@RestController
@RequiredArgsConstructor
public class ResenasController {

    private final ResenaService resenaService;

    @Operation(summary = "Calificar una cita atendida (paciente)",
            description = "Solo citas COMPLETADA del propio paciente, una vez por cita. 409 si no fue atendida o ya tiene reseña.")
    @PostMapping("/api/citas/{citaId}/resena")
    @ResponseStatus(HttpStatus.CREATED)
    public ResenaResponse crear(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId,
                                @Valid @RequestBody ResenaRequest request) {
        return resenaService.crear(principal.getUsuario().getId(), citaId, request);
    }

    @Operation(summary = "Editar mi reseña de una cita (paciente)")
    @PatchMapping("/api/citas/{citaId}/resena")
    public ResenaResponse editar(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId,
                                 @Valid @RequestBody ResenaRequest request) {
        return resenaService.editar(principal.getUsuario().getId(), citaId, request);
    }

    @Operation(summary = "Citas atendidas que aún no califiqué (paciente)")
    @GetMapping("/api/citas/por-resenar")
    public List<CitaPorResenar> porResenar(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return resenaService.porResenar(principal.getUsuario().getId());
    }

    @Operation(summary = "Reseñas de un odontólogo (sin login)",
            description = "Promedio, total, cuántas hay de cada puntaje (5 a 1) y las reseñas más recientes. Página desde 0.")
    @SecurityRequirements
    @GetMapping("/api/odontologos/{odontologoId}/resenas")
    public ResenasOdontologoResponse deOdontologo(@PathVariable UUID odontologoId,
                                                  @RequestParam(defaultValue = "0") int pagina,
                                                  @RequestParam(defaultValue = "10") int tamano) {
        return resenaService.deOdontologo(odontologoId, pagina, tamano);
    }
}

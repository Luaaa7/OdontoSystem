package com.odontosystem.OdontoSystem.resenas;

import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.Pagina;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenaRequest;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenaResponse;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Reseñas", description = "Calificaciones de pacientes a odontólogos tras una cita atendida")
@RestController
@RequiredArgsConstructor
public class ResenasController {

    private final ResenaService resenaService;

    @Operation(summary = "Calificar una cita atendida (paciente)",
            description = "calificacion de 1 a 5 y comentario opcional (hasta 1000 caracteres). Solo el paciente de la cita, "
                    + "una vez que esté COMPLETADA y una sola reseña por cita (409 en los otros casos).")
    @PostMapping("/api/citas/{citaId}/resena")
    @ResponseStatus(HttpStatus.CREATED)
    public ResenaResponse crear(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId,
                                @Valid @RequestBody ResenaRequest request) {
        return resenaService.crear(principal.getUsuario().getId(), citaId, request);
    }

    @Operation(summary = "Reseñas de un odontólogo (sin login)",
            description = "De la más reciente a la más antigua. El paciente aparece abreviado (ej. \"Juan Q.\"). Página desde 0.")
    @SecurityRequirements
    @GetMapping("/api/odontologos/{odontologoId}/resenas")
    public Pagina<ResenaResponse> delOdontologo(@PathVariable UUID odontologoId,
                                                @RequestParam(defaultValue = "0") int pagina,
                                                @RequestParam(defaultValue = "20") int tamano) {
        return resenaService.delOdontologo(odontologoId, pagina, tamano);
    }
}
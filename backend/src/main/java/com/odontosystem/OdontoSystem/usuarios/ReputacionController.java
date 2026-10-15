package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import com.odontosystem.OdontoSystem.usuarios.ReputacionService.ReputacionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Mi cuenta", description = "Datos del usuario autenticado")
@RestController
@RequiredArgsConstructor
public class ReputacionController {

    private final ReputacionService reputacionService;

    @Operation(summary = "Mi semáforo de puntualidad (paciente)",
            description = "Strikes por inasistencia (menos las apelaciones aprobadas), límite antes del bloqueo y si "
                    + "todavía puede reservar. Con el límite de strikes (3) la cuenta queda BLOQUEADA para reservar.")
    @GetMapping("/api/usuarios/yo/reputacion")
    public ReputacionResponse miReputacion(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return reputacionService.deMiCuenta(principal.getUsuario().getId());
    }
}

package com.odontosystem.OdontoSystem.odontologos;

import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import com.odontosystem.OdontoSystem.service.VerificacionColegiaturaService;
import com.odontosystem.OdontoSystem.service.VerificacionColegiaturaService.EstadoVerificacionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Verificación COP", description = "RF09: estado de la verificación de la colegiatura y reintento")
@RestController
@RequiredArgsConstructor
public class VerificacionController {

    private final VerificacionColegiaturaService verificacion;

    @Operation(summary = "Estado de mi verificación COP (odontólogo)",
            description = "PENDIENTE (en revisión o el COP no respondió; se reintenta solo), VERIFICADO, OBSERVADO o RECHAZADO.")
    @GetMapping("/api/odontologos/yo/verificacion")
    public EstadoVerificacionResponse estado(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return verificacion.estado(principal.getUsuario().getId());
    }

    @Operation(summary = "Reintentar ahora la verificación COP (odontólogo)",
            description = "Solo si sigue PENDIENTE; como máximo una vez cada 10 minutos (429 si es antes).")
    @PostMapping("/api/odontologos/yo/verificacion/reintentar")
    public EstadoVerificacionResponse reintentar(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return verificacion.reintentarAhora(principal.getUsuario().getId());
    }
}

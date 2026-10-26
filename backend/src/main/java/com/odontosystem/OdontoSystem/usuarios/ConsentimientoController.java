package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import com.odontosystem.OdontoSystem.usuarios.ConsentimientoService.ConsentimientoResponse;
import com.odontosystem.OdontoSystem.usuarios.ConsentimientoService.PoliticaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Privacidad", description = "RNF07: consentimiento de la política de privacidad (Ley N.º 29733)")
@RestController
@RequiredArgsConstructor
public class ConsentimientoController {

    private final ConsentimientoService consentimientoService;

    @Operation(summary = "Versión vigente de la política de privacidad (sin login)",
            description = "El formulario de registro la muestra junto a la casilla \"Acepto la política de privacidad\".")
    @SecurityRequirements
    @GetMapping("/api/public/privacidad")
    public PoliticaResponse politica() {
        return new PoliticaResponse(consentimientoService.versionVigente());
    }

    @Operation(summary = "Mis consentimientos", description = "Qué versión de la política aceptaste y cuándo.")
    @GetMapping("/api/usuarios/yo/consentimientos")
    public List<ConsentimientoResponse> mios(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return consentimientoService.deUsuario(principal.getUsuario().getId());
    }
}

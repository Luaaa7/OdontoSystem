package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import com.odontosystem.OdontoSystem.usuarios.ConsentimientoService.ConsentimientoResponse;
import com.odontosystem.OdontoSystem.usuarios.ConsentimientoService.EstadoConsentimiento;
import com.odontosystem.OdontoSystem.usuarios.ConsentimientoService.PoliticaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    @Operation(summary = "¿Acepté la política vigente?",
            description = "aceptada = false cuando la política cambió de versión: el frontend debe mostrarla y pedir "
                    + "que se acepte con POST /api/usuarios/yo/consentimientos.")
    @GetMapping("/api/usuarios/yo/consentimientos/estado")
    public EstadoConsentimiento estado(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return consentimientoService.estado(principal.getUsuario().getId());
    }

    @Operation(summary = "Aceptar la política de privacidad vigente",
            description = "Guarda la versión, la fecha y la IP (Ley N.º 29733). Si ya estaba aceptada, no se duplica.")
    @PostMapping("/api/usuarios/yo/consentimientos")
    public EstadoConsentimiento aceptar(@AuthenticationPrincipal UsuarioPrincipal principal,
                                        HttpServletRequest request) {
        return consentimientoService.aceptar(principal.getUsuario().getId(), request.getRemoteAddr());
    }
}

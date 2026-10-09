package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import com.odontosystem.OdontoSystem.usuarios.UsuarioService.ActualizarUsuarioRequest;
import com.odontosystem.OdontoSystem.usuarios.UsuarioService.UsuarioResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Mi cuenta", description = "Datos del usuario autenticado")
@RestController
@RequestMapping("/api/usuarios/yo")
@RequiredArgsConstructor
public class UsuarioController {

    private final UsuarioService usuarioService;

    @Operation(summary = "Mis datos", description = "Lo usa el frontend al iniciar para mostrar nombre, rol y foto.")
    @GetMapping
    public UsuarioResponse yo(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return usuarioService.obtener(principal.getUsuario().getId());
    }

    @Operation(summary = "Actualizar mis datos", description = "Nombre y teléfono. Para la foto usa PUT /api/usuarios/yo/foto.")
    @PatchMapping
    public UsuarioResponse actualizar(@AuthenticationPrincipal UsuarioPrincipal principal,
                                      @Valid @RequestBody ActualizarUsuarioRequest request) {
        return usuarioService.actualizar(principal.getUsuario().getId(), request);
    }
}

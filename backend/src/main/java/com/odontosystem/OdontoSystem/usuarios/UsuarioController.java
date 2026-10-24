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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Mi cuenta", description = "Datos del usuario autenticado")
@RestController
@RequestMapping("/api/usuarios/yo")
@RequiredArgsConstructor
public class UsuarioController {

    private final UsuarioService usuarioService;
    private final VerificacionCorreoService verificacionCorreo;

    public record CodigoCorreoRequest(@NotBlank @Pattern(regexp = "\\d{6}", message = "El código tiene 6 dígitos") String codigo) {}

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

    @Operation(summary = "Verificar mi correo: pedir código (OTP)",
            description = "Envía un código de 6 dígitos al correo de la cuenta (vale 15 minutos). 204 enviado; 409 si ya está "
                    + "verificado; 429 si pidió otro hace menos de 1 minuto. Al registrarse ya se envía uno automáticamente.")
    @PostMapping("/verificar-correo/codigo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pedirCodigoCorreo(@AuthenticationPrincipal UsuarioPrincipal principal) {
        verificacionCorreo.enviarCodigo(principal.getUsuario().getId());
    }

    @Operation(summary = "Verificar mi correo: ingresar el código (OTP)",
            description = "Body: codigo (6 dígitos). Devuelve mis datos con correo_verificado = true. 400 si el código es "
                    + "incorrecto o venció (5 intentos como máximo; luego hay que pedir otro).")
    @PostMapping("/verificar-correo")
    public UsuarioResponse verificarCorreo(@AuthenticationPrincipal UsuarioPrincipal principal,
                                           @Valid @RequestBody CodigoCorreoRequest request) {
        return verificacionCorreo.verificar(principal.getUsuario().getId(), request.codigo());
    }
}

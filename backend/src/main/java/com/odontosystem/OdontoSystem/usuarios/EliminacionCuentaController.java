package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Eliminación de la cuenta desde la app y desde la web (requisito de Google Play). */
@Tag(name = "Eliminar cuenta", description = "Google Play / Ley N.º 29733: el usuario puede eliminar su cuenta")
@Controller
public class EliminacionCuentaController {

    private final EliminacionCuentaService eliminacion;

    public EliminacionCuentaController(EliminacionCuentaService eliminacion) {
        this.eliminacion = eliminacion;
    }

    public record EliminarMiCuentaRequest(@Size(max = 100) String password, @NotBlank String confirmacion) {}

    public record CorreoRequest(@NotBlank @Email @Size(max = 150) String correo) {}

    public record EliminarConCodigoRequest(@NotBlank @Email @Size(max = 150) String correo,
                                           @NotBlank @Pattern(regexp = "\\d{6}", message = "El código tiene 6 dígitos") String codigo) {}

    @Operation(summary = "Eliminar mi cuenta (desde la app)",
            description = "Body: confirmacion = \"ELIMINAR\" y password (obligatoria si la cuenta tiene contraseña; las de "
                    + "Google solo confirman). Cancela las citas futuras, borra sesiones, dispositivos, OTP y chats, y "
                    + "anonimiza los datos personales. Se conservan sin datos de contacto las citas, pagos e historias "
                    + "clínicas que exige la ley. 204 hecho; 400 contraseña o confirmación incorrecta.")
    @PostMapping("/api/usuarios/yo/eliminar-cuenta")
    @ResponseBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarMiCuenta(@AuthenticationPrincipal UsuarioPrincipal principal,
                                 @Valid @RequestBody EliminarMiCuentaRequest request) {
        eliminacion.eliminarMiCuenta(principal.getUsuario().getId(), request.password(), request.confirmacion());
    }

    @Operation(summary = "Eliminar cuenta sin la app: pedir código (público)",
            description = "Envía un código de 6 dígitos al correo si existe una cuenta activa. Responde 204 siempre, "
                    + "exista o no (no revela qué correos están registrados). Lo usa la página /eliminar-cuenta.")
    @SecurityRequirements
    @PostMapping("/api/public/cuenta/eliminacion/codigo")
    @ResponseBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pedirCodigo(@Valid @RequestBody CorreoRequest request) {
        eliminacion.solicitarCodigo(request.correo());
    }

    @Operation(summary = "Eliminar cuenta sin la app: confirmar con el código (público)",
            description = "Body: correo y codigo (6 dígitos, vence en 15 minutos, máximo 5 intentos). 204 cuenta eliminada.")
    @SecurityRequirements
    @PostMapping("/api/public/cuenta/eliminacion")
    @ResponseBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarConCodigo(@Valid @RequestBody EliminarConCodigoRequest request) {
        eliminacion.eliminarConCodigo(request.correo(), request.codigo());
    }

    /** Página pública para pedir la eliminación sin la app (es el enlace que se declara en Google Play). */
    @GetMapping("/eliminar-cuenta")
    public String pagina() {
        return "forward:/eliminar-cuenta.html";
    }
}

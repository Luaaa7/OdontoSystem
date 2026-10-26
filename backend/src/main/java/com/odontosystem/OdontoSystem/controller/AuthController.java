package com.odontosystem.OdontoSystem.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import com.odontosystem.OdontoSystem.dto.AuthResponse;
import com.odontosystem.OdontoSystem.dto.GoogleAuthResponse;
import com.odontosystem.OdontoSystem.dto.GoogleLoginRequest;
import com.odontosystem.OdontoSystem.dto.GoogleRegistroRequest;
import com.odontosystem.OdontoSystem.dto.LoginRequest;
import com.odontosystem.OdontoSystem.dto.RecuperarPasswordRequest;
import com.odontosystem.OdontoSystem.dto.RefreshRequest;
import com.odontosystem.OdontoSystem.dto.RegistroRequest;
import com.odontosystem.OdontoSystem.dto.RestablecerPasswordRequest;
import com.odontosystem.OdontoSystem.service.AuthGoogleService;
import com.odontosystem.OdontoSystem.service.AuthService;
import com.odontosystem.OdontoSystem.service.RecuperacionPasswordService;
import com.odontosystem.OdontoSystem.service.TokenRefrescoService;
import com.odontosystem.OdontoSystem.usuarios.VerificacionCorreoService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@SecurityRequirements // público: Swagger no muestra candado
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final TokenRefrescoService tokenRefrescoService;
    private final RecuperacionPasswordService recuperacion;
    private final AuthGoogleService authGoogle;
    private final VerificacionCorreoService verificacionCorreo;

    public AuthController(AuthService authService, TokenRefrescoService tokenRefrescoService,
                          RecuperacionPasswordService recuperacion, AuthGoogleService authGoogle,
                          VerificacionCorreoService verificacionCorreo) {
        this.authService = authService;
        this.tokenRefrescoService = tokenRefrescoService;
        this.recuperacion = recuperacion;
        this.authGoogle = authGoogle;
        this.verificacionCorreo = verificacionCorreo;
    }

    @Operation(summary = "Registrarse con correo y contraseña",
            description = "Crea la cuenta y entrega los tokens. Además envía un código de 6 dígitos al correo "
                    + "(correo_verificado = false): el paciente debe verificarlo en POST /api/usuarios/yo/verificar-correo "
                    + "antes de reservar.")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> registrar(@Valid @RequestBody RegistroRequest request, HttpServletRequest httpRequest) {
        AuthResponse response = authService.registrar(request, httpRequest);
        verificacionCorreo.enviarAlRegistrarse(request.correo());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        AuthResponse response = authService.login(request, httpRequest);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refrescar(@Valid @RequestBody RefreshRequest request, HttpServletRequest httpRequest) {
        AuthResponse response = authService.refrescar(request.refreshToken(), httpRequest);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Cerrar sesión",
            description = "Revoca el refresh token de este dispositivo. El access token deja de servir cuando vence (15 min), "
                    + "así que el frontend debe borrarlo. Responde 204 aunque el token ya no exista.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        tokenRefrescoService.revocar(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Olvidé mi contraseña: pedir código",
            description = "Envía un código de 6 dígitos al correo (vale 15 min; uno cada 60 s). Responde 202 siempre, "
                    + "exista o no la cuenta, para no revelar qué correos están registrados.")
    @PostMapping("/recuperar-password")
    public ResponseEntity<Void> recuperarPassword(@Valid @RequestBody RecuperarPasswordRequest request) {
        recuperacion.solicitar(request.correo());
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Olvidé mi contraseña: cambiarla con el código",
            description = "Cambia la contraseña y cierra todas las sesiones abiertas. 400 si el código es incorrecto o venció "
                    + "(5 intentos como máximo por código).")
    @PostMapping("/restablecer-password")
    public ResponseEntity<Void> restablecerPassword(@Valid @RequestBody RestablecerPasswordRequest request) {
        recuperacion.restablecer(request.correo(), request.codigo(), request.passwordNueva());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Entrar o registrarse con Google (RF16)",
            description = "Solo id_token: si la cuenta existe (o hay una con el mismo correo, que se liga) responde "
                    + "perfil_completo = true con los tokens; si es nueva responde perfil_completo = false con correo y "
                    + "nombre_completo de Google. Para crearla, se vuelve a llamar con id_token + rol, tipo_documento, "
                    + "numero_documento, telefono, numero_colegiatura (odontólogo) y acepta_privacidad = true.")
    @PostMapping("/google")
    public ResponseEntity<GoogleAuthResponse> entrarConGoogle(@Valid @RequestBody GoogleLoginRequest request,
                                                              HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authGoogle.continuar(request, httpRequest));
    }

    @Operation(summary = "Registrarse con Google (RF16)",
            description = "Correo y nombre salen de Google; se completan documento, teléfono, rol (y COP si es odontólogo) "
                    + "y la aceptación de la política de privacidad. 201 con los tokens.")
    @PostMapping("/google/registro")
    public ResponseEntity<AuthResponse> registrarConGoogle(@Valid @RequestBody GoogleRegistroRequest request,
                                                           HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authGoogle.registrar(request, httpRequest));
    }
}

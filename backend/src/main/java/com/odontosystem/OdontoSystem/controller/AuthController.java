package com.odontosystem.OdontoSystem.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import com.odontosystem.OdontoSystem.dto.AuthResponse;
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

    public AuthController(AuthService authService, TokenRefrescoService tokenRefrescoService,
                          RecuperacionPasswordService recuperacion, AuthGoogleService authGoogle) {
        this.authService = authService;
        this.tokenRefrescoService = tokenRefrescoService;
        this.recuperacion = recuperacion;
        this.authGoogle = authGoogle;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> registrar(@Valid @RequestBody RegistroRequest request, HttpServletRequest httpRequest) {
        AuthResponse response = authService.registrar(request, httpRequest);
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

    @Operation(summary = "Entrar con Google (RF16)",
            description = "Recibe el ID token de Google Sign-In. 200 con los tokens si hay cuenta (si existe una cuenta con "
                    + "el mismo correo, se liga). 404 si no hay cuenta: el frontend debe mostrar el registro con Google.")
    @PostMapping("/google")
    public ResponseEntity<AuthResponse> entrarConGoogle(@Valid @RequestBody GoogleLoginRequest request,
                                                        HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authGoogle.entrar(request.idToken(), httpRequest));
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

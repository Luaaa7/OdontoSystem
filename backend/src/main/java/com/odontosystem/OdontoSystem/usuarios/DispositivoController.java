package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.notificaciones.PushService;
import com.odontosystem.OdontoSystem.notificaciones.PushService.DispositivoRequest;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notificaciones push", description = "Registro del celular para recibir avisos y recordatorios (Firebase)")
@RestController
public class DispositivoController {

    private final PushService push;

    public DispositivoController(PushService push) {
        this.push = push;
    }

    @Operation(summary = "Registrar este celular para push",
            description = "Body: token (el que entrega Firebase Messaging en la app) y sistema_operativo (ANDROID, IOS o WEB). "
                    + "Llamarlo al iniciar sesión y cada vez que Firebase renueve el token. 204.")
    @PostMapping("/api/usuarios/yo/dispositivos")
    public ResponseEntity<Void> registrar(@AuthenticationPrincipal UsuarioPrincipal principal,
                                          @Valid @RequestBody DispositivoRequest request) {
        push.registrar(principal.getUsuario().getId(), request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Dejar de recibir push en este celular",
            description = "Llamarlo al cerrar sesión, antes de POST /api/auth/logout. Body: token. 204.")
    @PostMapping("/api/usuarios/yo/dispositivos/quitar")
    public ResponseEntity<Void> quitar(@AuthenticationPrincipal UsuarioPrincipal principal,
                                       @Valid @RequestBody DispositivoRequest request) {
        push.quitar(principal.getUsuario().getId(), request.token());
        return ResponseEntity.noContent().build();
    }
}

package com.odontosystem.OdontoSystem.notificaciones;

import com.odontosystem.OdontoSystem.notificaciones.BandejaNotificaciones.BandejaResponse;
import com.odontosystem.OdontoSystem.notificaciones.BandejaNotificaciones.LeidasResponse;
import com.odontosystem.OdontoSystem.notificaciones.BandejaNotificaciones.NotificacionResponse;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** La campanita: avisos del usuario autenticado (paciente u odontólogo). */
@Tag(name = "Notificaciones", description = "Bandeja de avisos de la app: listar y marcar como leídos")
@RestController
@RequestMapping("/api/usuarios/yo/notificaciones")
@RequiredArgsConstructor
public class NotificacionesController {

    private final BandejaNotificaciones bandeja;

    @Operation(summary = "Mis notificaciones",
            description = "Las 50 más recientes (la más nueva primero) y cuántas no he leído. creado_en es cuándo "
                    + "ocurrió el aviso, no la fecha de la cita.")
    @GetMapping
    public BandejaResponse mias(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return bandeja.deUsuario(principal.getUsuario().getId());
    }

    @Operation(summary = "Marcar una notificación como leída")
    @PatchMapping("/{notificacionId}/leida")
    public NotificacionResponse marcarLeida(@AuthenticationPrincipal UsuarioPrincipal principal,
                                            @PathVariable UUID notificacionId) {
        return bandeja.marcarLeida(principal.getUsuario().getId(), notificacionId);
    }

    @Operation(summary = "Marcar todas como leídas")
    @PatchMapping("/leidas")
    public LeidasResponse marcarTodas(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return bandeja.marcarTodasLeidas(principal.getUsuario().getId());
    }
}

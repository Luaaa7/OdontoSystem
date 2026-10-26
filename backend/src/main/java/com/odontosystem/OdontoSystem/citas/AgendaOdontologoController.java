package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.CitaDtos.CitaResponse;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Agenda del odontólogo (rol ODONTOLOGO, por /api/odontologos/yo/** en SecurityConfig). */
@Tag(name = "Mi agenda", description = "Citas del odontólogo autenticado y acciones sobre ellas")
@RestController
@RequestMapping("/api/odontologos/yo/citas")
@RequiredArgsConstructor
public class AgendaOdontologoController {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private final CitaService citaService;

    private static UUID id(UsuarioPrincipal principal) {
        return principal.getUsuario().getId();
    }

    @Operation(summary = "Mi agenda", description = "Citas entre desde y hasta (YYYY-MM-DD, hasta exclusivo). Por defecto: hoy y los próximos 7 días.")
    @GetMapping
    public List<CitaResponse> agenda(@AuthenticationPrincipal UsuarioPrincipal principal,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        LocalDate inicio = desde != null ? desde : LocalDate.now(LIMA);
        LocalDate fin = hasta != null ? hasta : inicio.plusDays(7);
        return citaService.agenda(id(principal), inicio.atStartOfDay(LIMA).toOffsetDateTime(),
                fin.atStartOfDay(LIMA).toOffsetDateTime());
    }

    @Operation(summary = "Confirmar una reserva", description = "Solo si estaba PENDIENTE_CONFIRMACION (confirmación manual activada).")
    @PatchMapping("/{citaId}/confirmar")
    public CitaResponse confirmar(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId) {
        return citaService.confirmar(id(principal), citaId);
    }

    @Operation(summary = "Marcar como atendida", description = "Cita CONFIRMADA que ya empezó.")
    @PatchMapping("/{citaId}/completar")
    public CitaResponse completar(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId) {
        return citaService.completar(id(principal), citaId);
    }

    @Operation(summary = "Marcar inasistencia",
            description = "Después de 15 min de tolerancia. Suma un strike al paciente; con 3 no puede volver a reservar.")
    @PatchMapping("/{citaId}/inasistencia")
    public CitaResponse inasistencia(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId) {
        return citaService.marcarInasistencia(id(principal), citaId);
    }
}

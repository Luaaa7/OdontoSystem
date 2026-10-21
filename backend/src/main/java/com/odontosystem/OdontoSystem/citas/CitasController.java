package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.CitaDtos.CancelacionRequest;
import com.odontosystem.OdontoSystem.citas.CitaDtos.CitaResponse;
import com.odontosystem.OdontoSystem.citas.CitaDtos.DisponibilidadResponse;
import com.odontosystem.OdontoSystem.citas.CitaDtos.ReservaRequest;
import com.odontosystem.OdontoSystem.citas.PagoService.PagoRequest;
import com.odontosystem.OdontoSystem.citas.PagoService.PagoResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Citas", description = "Disponibilidad, reserva y seguimiento de citas del paciente")
@RestController
@RequiredArgsConstructor
public class CitasController {

    private final DisponibilidadService disponibilidadService;
    private final CitaService citaService;
    private final PagoService pagoService;

    @Operation(summary = "Turnos libres de un odontólogo (sin login)",
            description = "Para un servicio y un día (YYYY-MM-DD, hasta 60 días adelante). Cada turno trae inicio y fin con zona horaria.")
    @SecurityRequirements
    @GetMapping("/api/odontologos/{odontologoId}/disponibilidad")
    public DisponibilidadResponse disponibilidad(@PathVariable UUID odontologoId,
                                                 @RequestParam(name = "servicio_id") UUID servicioId,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return disponibilidadService.disponibilidad(odontologoId, servicioId, fecha);
    }

    @Operation(summary = "Reservar una cita (paciente)",
            description = "Usa el inicio exacto de un turno de /disponibilidad. Queda PENDIENTE_PAGO: hay 15 minutos para pagar "
                    + "el depósito (pagar_antes_de); si no, el turno se libera solo. 409 si el turno ya no está libre.")
    @PostMapping("/api/citas")
    @ResponseStatus(HttpStatus.CREATED)
    public CitaResponse reservar(@AuthenticationPrincipal UsuarioPrincipal principal,
                                 @Valid @RequestBody ReservaRequest request) {
        return citaService.reservar(principal.getUsuario().getId(), request);
    }

    @Operation(summary = "Pagar el depósito (paciente)",
            description = "Body: metodo (TARJETA, YAPE o PLIN) y, con Culqi, token (tkn_… de tarjeta o ype_… de Yape, que entrega "
                    + "Culqi Checkout en el frontend con la llave de GET /api/public/pagos/config). Respuesta: estado VERIFICADO "
                    + "(pagado: la cita queda CONFIRMADA o PENDIENTE_CONFIRMACION), RECHAZADO (puede reintentar con otro medio; "
                    + "ver mensaje) o PENDIENTE (Culqi no respondió: se confirma solo en minutos, no volver a pagar). "
                    + "En modo simulado no hace falta token. 409 si ya pasaron los 15 minutos o hay un pago en confirmación.")
    @PostMapping("/api/citas/{citaId}/pagar")
    public PagoResponse pagar(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId,
                              @Valid @RequestBody PagoRequest request) {
        return pagoService.pagarDeposito(principal.getUsuario().getId(), citaId, request.metodo(), request.token());
    }

    @Operation(summary = "Mis citas (paciente)", description = "De la más reciente a la más antigua.")
    @GetMapping("/api/citas/mias")
    public List<CitaResponse> misCitas(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return citaService.misCitas(principal.getUsuario().getId());
    }

    @Operation(summary = "Detalle de una cita", description = "Solo para el paciente o el odontólogo de la cita.")
    @GetMapping("/api/citas/{citaId}")
    public CitaResponse detalle(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId) {
        return citaService.detalle(principal.getUsuario().getId(), citaId);
    }

    @Operation(summary = "Cancelar una cita (paciente u odontólogo)",
            description = "Con reembolso si la cancela el odontólogo o si el paciente avisa con 12 h o más; sin pago no hay reembolso.")
    @PatchMapping("/api/citas/{citaId}/cancelar")
    public CitaResponse cancelar(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId,
                                 @Valid @RequestBody(required = false) CancelacionRequest request) {
        return citaService.cancelar(principal.getUsuario().getId(), citaId, request == null ? null : request.motivo());
    }
}

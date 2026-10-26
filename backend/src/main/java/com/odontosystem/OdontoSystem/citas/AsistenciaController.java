package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.AgendaDiariaService.AgendaHoyResponse;
import com.odontosystem.OdontoSystem.citas.AgendaDiariaService.ConfirmacionRequest;
import com.odontosystem.OdontoSystem.citas.AsistenciaService.AsistenciaRequest;
import com.odontosystem.OdontoSystem.citas.AsistenciaService.AsistenciaResponse;
import com.odontosystem.OdontoSystem.citas.AsistenciaService.ReporteResponse;
import com.odontosystem.OdontoSystem.citas.AsistenciaService.RespuestaReporteRequest;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Asistencia", description = "Confirmación diaria de agenda, \"¿te atendieron?\" y reportes de ausencia del odontólogo")
@RestController
@RequiredArgsConstructor
public class AsistenciaController {

    private final AgendaDiariaService agendaDiaria;
    private final AsistenciaService asistencia;

    @Operation(summary = "¿Te atendieron? (paciente)",
            description = "Desde 15 min después de la hora de la cita y hasta 72 h después de que termine. atendido=true cierra "
                    + "la cita como atendida. atendido=false reporta que el odontólogo no llegó: si es durante la cita (hasta "
                    + "2 h después del fin) envía latitud y longitud, debes estar a menos de 200 m del consultorio. El "
                    + "odontólogo tiene 48 h para responder con prueba; si no responde, se te devuelve el 100 %.")
    @PostMapping("/api/citas/{citaId}/asistencia")
    public AsistenciaResponse responder(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId,
                                        @Valid @RequestBody AsistenciaRequest request) {
        return asistencia.responderPaciente(principal.getUsuario().getId(), citaId, request);
    }

    @Operation(summary = "Mi agenda de hoy (odontólogo)",
            description = "atiende = null si todavía no confirmaste hoy. Sin confirmar, no se aceptan reservas para hoy.")
    @GetMapping("/api/odontologos/yo/agenda-hoy")
    public AgendaHoyResponse agendaHoy(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return agendaDiaria.hoy(principal.getUsuario().getId());
    }

    @Operation(summary = "¿Atiendes hoy? (odontólogo)",
            description = "atiende=true abre las reservas de hoy. atiende=false cancela tus citas pendientes de hoy con "
                    + "reembolso del 100 % (cada paciente recibe un correo) y bloquea el resto del día.")
    @PostMapping("/api/odontologos/yo/agenda-hoy")
    public AgendaHoyResponse confirmarHoy(@AuthenticationPrincipal UsuarioPrincipal principal,
                                          @Valid @RequestBody ConfirmacionRequest request) {
        return agendaDiaria.confirmar(principal.getUsuario().getId(), request);
    }

    @Operation(summary = "Reportes de pacientes que dicen que no los atendiste (odontólogo)")
    @GetMapping("/api/odontologos/yo/reportes-ausencia")
    public List<ReporteResponse> reportes(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return asistencia.delOdontologo(principal.getUsuario().getId());
    }

    @Operation(summary = "Responder un reporte de ausencia (odontólogo)",
            description = "atendi=false: admites la falta (reembolso al paciente y una falta para ti). atendi=true: la cita "
                    + "queda atendida; registra su historia clínica como prueba (si ya está, el reporte se rechaza al instante "
                    + "y el paciente recibe un strike). Sin prueba al vencer las 48 h, pasa a reclamo.")
    @PostMapping("/api/odontologos/yo/reportes-ausencia/{reporteId}/respuesta")
    public ReporteResponse responderReporte(@AuthenticationPrincipal UsuarioPrincipal principal,
                                            @PathVariable UUID reporteId,
                                            @Valid @RequestBody RespuestaReporteRequest request) {
        return asistencia.responderOdontologo(principal.getUsuario().getId(), reporteId, request);
    }
}

package com.odontosystem.OdontoSystem.odontologos;

import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ActualizarPerfilRequest;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.BloqueoRequest;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.BloqueoResponse;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.HorarioDto;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.HorariosRequest;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.MiPerfilResponse;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ServicioRequest;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ServicioResponse;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Panel del odontólogo: requiere JWT con rol ODONTOLOGO (SecurityConfig, /api/odontologos/yo/**). */
@Tag(name = "Mi consultorio", description = "Perfil, servicios, horario y bloqueos del odontólogo autenticado")
@RestController
@RequestMapping("/api/odontologos/yo")
@RequiredArgsConstructor
public class MiConsultorioController {

    private final PerfilOdontologoService perfilService;
    private final MiConsultorioService consultorioService;

    private static UUID id(UsuarioPrincipal principal) {
        return principal.getUsuario().getId();
    }

    @Operation(summary = "Mi perfil", description = "Incluye estado de verificación, nota de observación y servicios desactivados.")
    @GetMapping("/perfil")
    public MiPerfilResponse miPerfil(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return perfilService.miPerfil(id(principal));
    }

    @Operation(summary = "Actualizar mi perfil",
            description = "Parcial: solo cambia lo que envíes. especialidad_ids reemplaza la lista completa. Latitud y longitud van juntas.")
    @PatchMapping("/perfil")
    public MiPerfilResponse actualizarPerfil(@AuthenticationPrincipal UsuarioPrincipal principal,
                                             @Valid @RequestBody ActualizarPerfilRequest request) {
        return perfilService.actualizarMiPerfil(id(principal), request);
    }

    @Operation(summary = "Mis servicios", description = "Activos e inactivos.")
    @GetMapping("/servicios")
    public List<ServicioResponse> servicios(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return consultorioService.listarServicios(id(principal));
    }

    @Operation(summary = "Crear servicio", description = "El depósito (20 % del precio) lo calcula la base de datos.")
    @PostMapping("/servicios")
    @ResponseStatus(HttpStatus.CREATED)
    public ServicioResponse crearServicio(@AuthenticationPrincipal UsuarioPrincipal principal,
                                          @Valid @RequestBody ServicioRequest request) {
        return consultorioService.crearServicio(id(principal), request);
    }

    @Operation(summary = "Editar servicio", description = "Las citas ya reservadas conservan el precio con el que se reservaron.")
    @PutMapping("/servicios/{servicioId}")
    public ServicioResponse actualizarServicio(@AuthenticationPrincipal UsuarioPrincipal principal,
                                               @PathVariable UUID servicioId,
                                               @Valid @RequestBody ServicioRequest request) {
        return consultorioService.actualizarServicio(id(principal), servicioId, request);
    }

    @Operation(summary = "Desactivar servicio", description = "Deja de mostrarse y de poder reservarse; no se borra por las citas pasadas.")
    @DeleteMapping("/servicios/{servicioId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desactivarServicio(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID servicioId) {
        consultorioService.desactivarServicio(id(principal), servicioId);
    }

    @Operation(summary = "Mi horario semanal")
    @GetMapping("/horarios")
    public List<HorarioDto> horarios(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return consultorioService.listarHorarios(id(principal));
    }

    @Operation(summary = "Guardar mi horario semanal",
            description = "Reemplaza el horario completo. Varios tramos por día están permitidos si no se cruzan. "
                    + "intervalo_minutos = cada cuánto empieza un turno (por defecto 30).")
    @PutMapping("/horarios")
    public List<HorarioDto> guardarHorarios(@AuthenticationPrincipal UsuarioPrincipal principal,
                                            @Valid @RequestBody HorariosRequest request) {
        return consultorioService.reemplazarHorarios(id(principal), request.horarios());
    }

    @Operation(summary = "Mis bloqueos vigentes", description = "Vacaciones, congresos o emergencias que cierran la agenda.")
    @GetMapping("/bloqueos")
    public List<BloqueoResponse> bloqueos(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return consultorioService.listarBloqueos(id(principal));
    }

    @Operation(summary = "Bloquear agenda", description = "Fechas con zona horaria, ej. 2026-10-20T09:00:00-05:00. Máximo 60 días.")
    @PostMapping("/bloqueos")
    @ResponseStatus(HttpStatus.CREATED)
    public BloqueoResponse crearBloqueo(@AuthenticationPrincipal UsuarioPrincipal principal,
                                        @Valid @RequestBody BloqueoRequest request) {
        return consultorioService.crearBloqueo(id(principal), request);
    }

    @Operation(summary = "Quitar un bloqueo")
    @DeleteMapping("/bloqueos/{bloqueoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarBloqueo(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID bloqueoId) {
        consultorioService.eliminarBloqueo(id(principal), bloqueoId);
    }
}

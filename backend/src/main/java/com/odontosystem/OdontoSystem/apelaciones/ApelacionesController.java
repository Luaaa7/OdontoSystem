package com.odontosystem.OdontoSystem.apelaciones;

import com.odontosystem.OdontoSystem.apelaciones.ApelacionDtos.ApelacionResponse;
import com.odontosystem.OdontoSystem.apelaciones.ApelacionDtos.ResolucionRequest;
import com.odontosystem.OdontoSystem.entity.EstadoApelacion;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Tag(name = "Apelaciones", description = "El paciente apela una inasistencia con un certificado; la resuelve el odontólogo de la cita")
@RestController
@RequiredArgsConstructor
public class ApelacionesController {

    private final ApelacionService apelacionService;

    @Operation(summary = "Apelar una inasistencia (paciente)",
            description = "multipart/form-data con el campo 'certificado' (PDF, JPG o PNG de hasta 5 MB). Solo citas "
                    + "INASISTENCIA_PENALIZADA, dentro de los 7 días siguientes y una vez por cita.")
    @PostMapping(value = "/api/citas/{citaId}/apelacion", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApelacionResponse apelar(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId,
                                    @RequestPart("certificado") MultipartFile certificado) {
        return apelacionService.apelar(principal.getUsuario().getId(), citaId, certificado);
    }

    @Operation(summary = "Estado de mi apelación (paciente)")
    @GetMapping("/api/citas/{citaId}/apelacion")
    public ApelacionResponse miApelacion(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId) {
        return apelacionService.deMiCita(principal.getUsuario().getId(), citaId);
    }

    @Operation(summary = "Apelaciones de mis pacientes (odontólogo)",
            description = "estado opcional: PENDIENTE, APROBADA o RECHAZADA. Cada una trae un enlace temporal al certificado.")
    @GetMapping("/api/odontologos/yo/apelaciones")
    public List<ApelacionResponse> delOdontologo(@AuthenticationPrincipal UsuarioPrincipal principal,
                                                 @RequestParam(required = false) EstadoApelacion estado) {
        return apelacionService.delOdontologo(principal.getUsuario().getId(), estado);
    }

    @Operation(summary = "Resolver una apelación (odontólogo)",
            description = "aprobar=true: se quita un strike al paciente y se le devuelve el depósito. aprobar=false: queda igual.")
    @PatchMapping("/api/odontologos/yo/apelaciones/{apelacionId}")
    public ApelacionResponse resolver(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID apelacionId,
                                      @Valid @RequestBody ResolucionRequest request) {
        return apelacionService.resolver(principal.getUsuario().getId(), apelacionId, request.aprobar(), request.nota());
    }
}

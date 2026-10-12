package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.ReembolsoService.ReembolsoResponse;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Reembolsos", description = "Devolución del depósito al cancelar con anticipación o al aprobarse una apelación")
@RestController
@RequiredArgsConstructor
public class ReembolsosController {

    private final ReembolsoService reembolsoService;

    @Operation(summary = "Reembolsos de una cita (paciente u odontólogo de la cita)",
            description = "Se crean solos al cancelar con reembolso o al aprobarse una apelación. Con la pasarela "
                    + "simulada quedan REEMBOLSADO al instante.")
    @GetMapping("/api/citas/{citaId}/reembolsos")
    public List<ReembolsoResponse> deCita(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId) {
        return reembolsoService.deCita(principal.getUsuario().getId(), citaId);
    }
}

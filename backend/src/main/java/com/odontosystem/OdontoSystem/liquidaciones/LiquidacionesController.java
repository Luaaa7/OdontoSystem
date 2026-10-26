package com.odontosystem.OdontoSystem.liquidaciones;

import com.odontosystem.OdontoSystem.liquidaciones.LiquidacionService.MisIngresos;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Liquidaciones", description = "Depósitos en custodia y pagos al odontólogo")
@RestController
@RequiredArgsConstructor
public class LiquidacionesController {

    private final LiquidacionService liquidacionService;

    @Operation(summary = "Mis ingresos por depósitos",
            description = "en_custodia: depósitos que la plataforma todavía guarda (se pagan 72 h después de la cita, "
                    + "o se retienen si hay un reclamo o apelación pendiente). pagado: ya transferido (simulado). "
                    + "devuelto_a_pacientes: depósitos que terminaron devueltos al paciente.")
    @GetMapping("/api/odontologos/yo/liquidaciones")
    public MisIngresos mias(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return liquidacionService.delOdontologo(principal.getUsuario().getId());
    }

    @Operation(summary = "Mis ingresos en Excel (.xlsx)",
            description = "Lo mismo que GET /api/odontologos/yo/liquidaciones, como hoja de cálculo (Apache POI): "
                    + "resumen y detalle de cada depósito, con filtros y montos en soles listos para sumar.")
    @GetMapping("/api/odontologos/yo/liquidaciones/excel")
    public ResponseEntity<byte[]> miasEnExcel(@AuthenticationPrincipal UsuarioPrincipal principal) {
        byte[] excel = ExportadorExcel.exportar(liquidacionService.delOdontologo(principal.getUsuario().getId()));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ExportadorExcel.TIPO))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mis-ingresos-odontosystem.xlsx\"")
                .body(excel);
    }
}

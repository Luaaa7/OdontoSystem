package com.odontosystem.OdontoSystem.constancias;

import com.odontosystem.OdontoSystem.constancias.ConstanciaService.Constancia;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Constancias", description = "RF14: constancia de pago del depósito (PDF, no tributaria)")
@RestController
@RequiredArgsConstructor
public class ConstanciasController {

    private final ConstanciaService constanciaService;

    @Operation(summary = "Descargar la constancia de pago del depósito (PDF)",
            description = "Para el paciente o el odontólogo de la cita, cuando el pago ya se verificó. No es boleta ni "
                    + "factura: el comprobante del servicio lo emite el odontólogo con su RUC. 409 si no hay depósito pagado.")
    @GetMapping(value = "/api/citas/{citaId}/constancia", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> descargar(@AuthenticationPrincipal UsuarioPrincipal principal, @PathVariable UUID citaId) {
        Constancia c = constanciaService.deCita(principal.getUsuario().getId(), citaId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(c.nombreArchivo()).build().toString())
                .body(c.pdf());
    }
}

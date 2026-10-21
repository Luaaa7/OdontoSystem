package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.PagoService.ResultadoAviso;
import com.odontosystem.OdontoSystem.citas.PasarelaCulqi.CulqiNoDisponible;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Configuración pública de pagos y avisos (webhooks) de Culqi. */
@Tag(name = "Pagos", description = "RF13: configuración de Culqi Checkout y avisos de la pasarela")
@SecurityRequirements
@RestController
public class PagosController {

    private static final Logger log = LoggerFactory.getLogger(PagosController.class);

    public record ConfiguracionPagos(String modo, String culqiLlavePublica, boolean modoPrueba, String moneda) {}

    private final PagoService pagos;
    private final PasarelaCulqi culqi;

    public PagosController(PagoService pagos, PasarelaCulqi culqi) {
        this.pagos = pagos;
        this.culqi = culqi;
    }

    @Operation(summary = "Configuración de pagos para el frontend",
            description = "modo: simulado o culqi. Con culqi, culqi_llave_publica (pk_test_… / pk_live_…) sirve para abrir "
                    + "Culqi Checkout; no es secreta. modo_prueba = true: ningún cobro es real (tarjetas de prueba de Culqi).")
    @GetMapping("/api/public/pagos/config")
    public ConfiguracionPagos configuracion() {
        boolean conCulqi = "culqi".equalsIgnoreCase(pagos.modo()) && culqi.configurada();
        return new ConfiguracionPagos(conCulqi ? "culqi" : "simulado", conCulqi ? culqi.llavePublica() : null,
                !conCulqi || culqi.modoPrueba(), "PEN");
    }

    @Operation(summary = "Aviso (webhook) de Culqi",
            description = "Lo llama Culqi, no el frontend. No se confía en el cuerpo: se consulta el cargo a Culqi con la llave "
                    + "secreta antes de confirmar nada. 200 si se procesó o se ignoró; 503 si Culqi no respondió (Culqi reintenta).")
    @PostMapping("/api/pagos/webhook/culqi")
    public ResponseEntity<Map<String, String>> avisoCulqi(@RequestBody(required = false) String cuerpo) {
        try {
            ResultadoAviso r = pagos.procesarAvisoCulqi(cuerpo == null ? "" : cuerpo);
            return ResponseEntity.ok(Map.of("resultado", r.name()));
        } catch (CulqiNoDisponible e) {
            log.warn("Aviso de Culqi sin procesar: Culqi no respondió ({})", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("resultado", "REINTENTAR"));
        }
    }
}

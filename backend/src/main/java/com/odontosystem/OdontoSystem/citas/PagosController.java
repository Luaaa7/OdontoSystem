package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.PagoService.ResultadoAviso;
import com.odontosystem.OdontoSystem.citas.PasarelaCulqi.CulqiNoDisponible;
import com.odontosystem.OdontoSystem.citas.PasarelaMercadoPago.MercadoPagoNoDisponible;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Configuración pública de pagos y avisos (webhooks) de Culqi y Mercado Pago. */
@Tag(name = "Pagos", description = "RF13: configuración de la pasarela (Culqi o Mercado Pago) y sus avisos")
@SecurityRequirements
@RestController
public class PagosController {

    private static final Logger log = LoggerFactory.getLogger(PagosController.class);

    public record ConfiguracionPagos(String modo, String culqiLlavePublica, String mercadopagoLlavePublica,
                                     boolean modoPrueba, String moneda) {}

    private final PagoService pagos;
    private final PasarelaCulqi culqi;
    private final PasarelaMercadoPago mercadoPago;

    public PagosController(PagoService pagos, PasarelaCulqi culqi, PasarelaMercadoPago mercadoPago) {
        this.pagos = pagos;
        this.culqi = culqi;
        this.mercadoPago = mercadoPago;
    }

    @Operation(summary = "Configuración de pagos para el frontend",
            description = "modo: simulado, culqi o mercadopago. La llave pública de la pasarela activa (culqi_llave_publica o "
                    + "mercadopago_llave_publica) sirve para abrir su formulario de pago; no es secreta. "
                    + "modo_prueba = true: ningún cobro es real (tarjetas de prueba).")
    @GetMapping("/api/public/pagos/config")
    public ConfiguracionPagos configuracion() {
        if ("mercadopago".equalsIgnoreCase(pagos.modo()) && mercadoPago.configurada()) {
            return new ConfiguracionPagos("mercadopago", null, mercadoPago.llavePublica(), mercadoPago.modoPrueba(), "PEN");
        }
        if ("culqi".equalsIgnoreCase(pagos.modo()) && culqi.configurada()) {
            return new ConfiguracionPagos("culqi", culqi.llavePublica(), null, culqi.modoPrueba(), "PEN");
        }
        return new ConfiguracionPagos("simulado", null, null, true, "PEN");
    }

    @Operation(summary = "Aviso (webhook) de Mercado Pago",
            description = "Lo llama Mercado Pago (tema Order), no el frontend. No se confía en el cuerpo: se consulta la orden "
                    + "a Mercado Pago con el Access Token antes de confirmar nada. 200 si se procesó o se ignoró; 503 si "
                    + "Mercado Pago no respondió (reintenta).")
    @PostMapping("/api/pagos/webhook/mercadopago")
    public ResponseEntity<Map<String, String>> avisoMercadoPago(@RequestBody(required = false) String cuerpo,
                                                                @RequestParam(name = "data.id", required = false) String idEnUrl) {
        try {
            ResultadoAviso r = pagos.procesarAvisoMercadoPago(cuerpo == null ? "" : cuerpo, idEnUrl);
            return ResponseEntity.ok(Map.of("resultado", r.name()));
        } catch (MercadoPagoNoDisponible e) {
            log.warn("Aviso de Mercado Pago sin procesar: no respondió ({})", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("resultado", "REINTENTAR"));
        }
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

package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.PagoService.ResultadoAviso;
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

import com.odontosystem.OdontoSystem.entity.MetodoPago;

import java.util.List;
import java.util.Map;

/** Configuración pública de pagos y avisos (webhooks) de Mercado Pago. */
@Tag(name = "Pagos", description = "RF13: configuración de la pasarela (Mercado Pago o simulada) y sus avisos")
@SecurityRequirements
@RestController
public class PagosController {

    private static final Logger log = LoggerFactory.getLogger(PagosController.class);

    /** disponible = false: el frontend lo muestra deshabilitado con la nota (p. ej. "Próximamente"). */
    public record MetodoDisponible(MetodoPago metodo, boolean disponible, String nota) {}

    public record ConfiguracionPagos(String modo, String mercadopagoLlavePublica, boolean modoPrueba, String moneda,
                                     List<MetodoDisponible> metodos) {}

    /** Plin todavía no lo cobra ninguna pasarela (Mercado Pago no lo ofrece): se muestra como "próximamente". */
    static final List<MetodoDisponible> METODOS = List.of(
            new MetodoDisponible(MetodoPago.TARJETA, true, null),
            new MetodoDisponible(MetodoPago.YAPE, true, null),
            new MetodoDisponible(MetodoPago.PLIN, false, "Próximamente"));

    private final PagoService pagos;
    private final PasarelaMercadoPago mercadoPago;

    public PagosController(PagoService pagos, PasarelaMercadoPago mercadoPago) {
        this.pagos = pagos;
        this.mercadoPago = mercadoPago;
    }

    @Operation(summary = "Configuración de pagos para el frontend",
            description = "modo: simulado o mercadopago. mercadopago_llave_publica sirve para abrir el formulario de pago "
                    + "(Bricks); no es secreta. modo_prueba = true: ningún cobro es real (tarjetas de prueba). metodos: "
                    + "TARJETA y YAPE disponibles; PLIN con disponible = false y nota \"Próximamente\" (mostrarlo deshabilitado).")
    @GetMapping("/api/public/pagos/config")
    public ConfiguracionPagos configuracion() {
        if ("mercadopago".equalsIgnoreCase(pagos.modo()) && mercadoPago.configurada()) {
            return new ConfiguracionPagos("mercadopago", mercadoPago.llavePublica(), mercadoPago.modoPrueba(), "PEN", METODOS);
        }
        return new ConfiguracionPagos("simulado", null, true, "PEN", METODOS);
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
}

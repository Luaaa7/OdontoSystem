package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.PagosController.MetodoDisponible;
import com.odontosystem.OdontoSystem.entity.MetodoPago;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PagosControllerTest {

    @Test
    @DisplayName("Configuración pública: modo simulado, tarjeta y Yape disponibles, Plin \"Próximamente\"")
    void configuracionSimulada() {
        PagoService pagos = mock(PagoService.class);
        when(pagos.modo()).thenReturn("simulado");

        var config = new PagosController(pagos, PasarelaMercadoPago.sinConfigurar()).configuracion();

        assertThat(config.modo()).isEqualTo("simulado");
        assertThat(config.mercadopagoLlavePublica()).isNull();
        assertThat(config.modoPrueba()).isTrue();
        assertThat(config.metodos()).containsExactly(
                new MetodoDisponible(MetodoPago.TARJETA, true, null),
                new MetodoDisponible(MetodoPago.YAPE, true, null),
                new MetodoDisponible(MetodoPago.PLIN, false, "Próximamente"));
    }

    @Test
    @DisplayName("Modo mercadopago sin credenciales: vuelve a simulado (no expone una pasarela que no funciona)")
    void mercadoPagoSinCredenciales() {
        PagoService pagos = mock(PagoService.class);
        when(pagos.modo()).thenReturn("mercadopago");

        var config = new PagosController(pagos, PasarelaMercadoPago.sinConfigurar()).configuracion();

        assertThat(config.modo()).isEqualTo("simulado");
    }
}

package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.PasarelaMercadoPago.MercadoPagoNoDisponible;
import com.odontosystem.OdontoSystem.citas.ReembolsoService.ReembolsoResponse;
import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.EstadoReembolso;
import com.odontosystem.OdontoSystem.entity.Pago;
import com.odontosystem.OdontoSystem.entity.PasarelaPago;
import com.odontosystem.OdontoSystem.entity.Reembolso;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.PagoRepository;
import com.odontosystem.OdontoSystem.repository.ReembolsoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RF15 con Mercado Pago: la devolución se pide a la API de Orders; si no responde queda SOLICITADO y se reintenta. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReembolsoMercadoPagoTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneOffset.UTC);
    private static final String ORDEN = "ORD01JS2V6CM8KJ0EC4H502TGK1WP";

    @Mock private PagoRepository pagos;
    @Mock private ReembolsoRepository reembolsos;
    @Mock private CitaRepository citas;
    @Mock private PasarelaCulqi culqi;
    @Mock private PasarelaMercadoPago mp;

    private final UUID citaId = UUID.randomUUID();
    private Pago pago;
    private ReembolsoService servicio;

    @BeforeEach
    void setUp() {
        pago = Pago.builder().id(UUID.randomUUID()).monto(new BigDecimal("16.00")).numeroOperacion(ORDEN)
                .pasarelaPago(PasarelaPago.MERCADO_PAGO).estado(EstadoPago.VERIFICADO).build();
        when(pagos.findFirstByCita_IdAndEstado(citaId, EstadoPago.VERIFICADO)).thenReturn(Optional.of(pago));
        when(reembolsos.saveAndFlush(any(Reembolso.class))).thenAnswer(i -> {
            Reembolso r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        when(mp.configurada()).thenReturn(true);
        servicio = new ReembolsoService(pagos, reembolsos, citas, new PasarelaSimulada(), culqi, mp, RELOJ, "mercadopago");
    }

    @Test
    @DisplayName("Pide la devolución de la orden a Mercado Pago y queda REEMBOLSADO con su id; no llama a Culqi")
    void devuelve() {
        when(mp.reembolsar(eq(ORDEN), anyString())).thenReturn("REF01");

        ReembolsoResponse r = servicio.reembolsarDeposito(citaId, "cancelacion:" + citaId, "Cancelada").orElseThrow();

        assertThat(r.estado()).isEqualTo(EstadoReembolso.REEMBOLSADO);
        assertThat(r.idExterno()).isEqualTo("REF01");
        assertThat(r.simulado()).isFalse();
        verify(culqi, never()).reembolsar(anyString(), anyLong());
    }

    @Test
    @DisplayName("Si Mercado Pago no responde queda SOLICITADO; la tarea lo reintenta y queda REEMBOLSADO")
    void reintenta() {
        when(mp.reembolsar(anyString(), anyString())).thenThrow(new MercadoPagoNoDisponible("timeout", null));
        ReembolsoResponse r = servicio.reembolsarDeposito(citaId, "cancelacion:" + citaId, null).orElseThrow();
        assertThat(r.estado()).isEqualTo(EstadoReembolso.SOLICITADO);

        Reembolso pendiente = Reembolso.builder().id(r.id()).pago(pago).monto(pago.getMonto())
                .estado(EstadoReembolso.SOLICITADO).build();
        when(reembolsos.findByEstado(EstadoReembolso.SOLICITADO)).thenReturn(List.of(pendiente));
        doReturn("REF02").when(mp).reembolsar(ORDEN, r.id().toString());

        assertThat(servicio.reintentarSolicitados()).isEqualTo(1);
        assertThat(pendiente.getEstado()).isEqualTo(EstadoReembolso.REEMBOLSADO);
        assertThat(pendiente.getIdExterno()).isEqualTo("REF02");
    }

    @Test
    @DisplayName("Un cobro real de Mercado Pago se devuelve de verdad aunque el sistema esté ahora en modo simulado")
    void realEnModoSimulado() {
        servicio = new ReembolsoService(pagos, reembolsos, citas, new PasarelaSimulada(), culqi, mp, RELOJ, "simulado");
        when(mp.reembolsar(eq(ORDEN), anyString())).thenReturn("REF03");

        ReembolsoResponse r = servicio.reembolsarDeposito(citaId, "cancelacion:" + citaId, null).orElseThrow();

        assertThat(r.idExterno()).isEqualTo("REF03");
        assertThat(r.simulado()).isFalse();
    }
}

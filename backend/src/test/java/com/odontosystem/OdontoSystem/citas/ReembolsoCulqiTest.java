package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.PasarelaCulqi.CulqiNoDisponible;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RF15 con Culqi: la devolución se pide a la API; si Culqi no responde queda SOLICITADO y se reintenta. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReembolsoCulqiTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-20T15:00:00Z"), ZoneOffset.UTC);

    @Mock private PagoRepository pagos;
    @Mock private ReembolsoRepository reembolsos;
    @Mock private CitaRepository citas;
    @Mock private PasarelaCulqi culqi;

    private final UUID citaId = UUID.randomUUID();
    private Pago pago;
    private ReembolsoService servicio;

    @BeforeEach
    void setUp() {
        pago = Pago.builder().id(UUID.randomUUID()).monto(new BigDecimal("16.00")).numeroOperacion("chr_test_1")
                .pasarelaPago(PasarelaPago.CULQI).estado(EstadoPago.VERIFICADO).build();
        when(pagos.findFirstByCita_IdAndEstado(citaId, EstadoPago.VERIFICADO)).thenReturn(Optional.of(pago));
        when(reembolsos.saveAndFlush(any(Reembolso.class))).thenAnswer(i -> {
            Reembolso r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        when(culqi.configurada()).thenReturn(true);
        servicio = new ReembolsoService(pagos, reembolsos, citas, new PasarelaSimulada(), culqi, RELOJ, "culqi");
    }

    @Test
    @DisplayName("Con Culqi: pide la devolución de 1600 céntimos del cargo y queda REEMBOLSADO con el id de Culqi")
    void devuelveConCulqi() {
        when(culqi.reembolsar("chr_test_1", 1600)).thenReturn("ref_test_1");

        ReembolsoResponse r = servicio.reembolsarDeposito(citaId, "cancelacion:" + citaId, "Cancelada").orElseThrow();

        assertThat(r.estado()).isEqualTo(EstadoReembolso.REEMBOLSADO);
        assertThat(r.idExterno()).isEqualTo("ref_test_1");
        assertThat(r.simulado()).isFalse();
    }

    @Test
    @DisplayName("Si Culqi no responde queda SOLICITADO; la tarea lo reintenta y queda REEMBOLSADO")
    void reintenta() {
        when(culqi.reembolsar(anyString(), anyLong())).thenThrow(new CulqiNoDisponible("timeout", null));
        ReembolsoResponse r = servicio.reembolsarDeposito(citaId, "cancelacion:" + citaId, null).orElseThrow();
        assertThat(r.estado()).isEqualTo(EstadoReembolso.SOLICITADO);

        Reembolso pendiente = Reembolso.builder().id(r.id()).pago(pago).monto(pago.getMonto())
                .estado(EstadoReembolso.SOLICITADO).build();
        when(reembolsos.findByEstado(EstadoReembolso.SOLICITADO)).thenReturn(List.of(pendiente));
        doReturn("ref_test_2").when(culqi).reembolsar("chr_test_1", 1600L);

        assertThat(servicio.reintentarSolicitados()).isEqualTo(1);
        assertThat(pendiente.getEstado()).isEqualTo(EstadoReembolso.REEMBOLSADO);
        assertThat(pendiente.getIdExterno()).isEqualTo("ref_test_2");
    }

    @Test
    @DisplayName("Un pago hecho en modo simulado se devuelve en modo simulado, sin llamar a Culqi")
    void pagoSimulado() {
        pago.setNumeroOperacion("sim_chr_abc");

        ReembolsoResponse r = servicio.reembolsarDeposito(citaId, "cancelacion:" + citaId, null).orElseThrow();

        assertThat(r.estado()).isEqualTo(EstadoReembolso.REEMBOLSADO);
        assertThat(r.simulado()).isTrue();
        verify(culqi, never()).reembolsar(anyString(), anyLong());
    }

    @Test
    @DisplayName("Sin llaves de Culqi la tarea de reintento no hace nada")
    void sinLlaves() {
        when(culqi.configurada()).thenReturn(false);
        assertThat(servicio.reintentarSolicitados()).isZero();
        verify(reembolsos, never()).findByEstado(any());
    }
}

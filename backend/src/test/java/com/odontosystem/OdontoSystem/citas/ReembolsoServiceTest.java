package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.ReembolsoService.ReembolsoResponse;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.EstadoReembolso;
import com.odontosystem.OdontoSystem.entity.Pago;
import com.odontosystem.OdontoSystem.entity.PasarelaPago;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Reembolso;
import com.odontosystem.OdontoSystem.entity.Usuario;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReembolsoServiceTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-20T15:00:00Z"), ZoneOffset.UTC);

    @Mock private PagoRepository pagos;
    @Mock private ReembolsoRepository reembolsos;
    @Mock private CitaRepository citas;

    private final UUID citaId = UUID.randomUUID();
    private Pago pago;

    @BeforeEach
    void setUp() {
        pago = Pago.builder().id(UUID.randomUUID()).monto(new BigDecimal("30.00"))
                .pasarelaPago(PasarelaPago.CULQI).estado(EstadoPago.VERIFICADO).build();
        when(pagos.findFirstByCita_IdAndEstado(citaId, EstadoPago.VERIFICADO)).thenReturn(Optional.of(pago));
        when(reembolsos.saveAndFlush(any(Reembolso.class))).thenAnswer(i -> {
            Reembolso r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
    }

    private ReembolsoService servicio(String modo) {
        return new ReembolsoService(pagos, reembolsos, citas, new PasarelaSimulada(),
                new PasarelaCulqi("", "", org.springframework.web.client.RestClient.builder()), RELOJ, modo);
    }

    @Test
    @DisplayName("Pasarela simulada: devuelve el depósito completo y queda REEMBOLSADO con su número de operación")
    void reembolsoSimulado() {
        ReembolsoResponse r = servicio("simulado").reembolsarDeposito(citaId, "cancelacion:" + citaId, "Cancelada").orElseThrow();

        assertThat(r.estado()).isEqualTo(EstadoReembolso.REEMBOLSADO);
        assertThat(r.monto()).isEqualByComparingTo("30.00");
        assertThat(r.idExterno()).startsWith("sim_rfd_");
        assertThat(r.simulado()).isTrue();
        assertThat(r.procesadoEn()).isNotNull();
    }

    @Test
    @DisplayName("Con Culqi (aún sin conectar) queda SOLICITADO hasta que la pasarela confirme")
    void reembolsoReal() {
        ReembolsoResponse r = servicio("culqi").reembolsarDeposito(citaId, "cancelacion:" + citaId, null).orElseThrow();

        assertThat(r.estado()).isEqualTo(EstadoReembolso.SOLICITADO);
        assertThat(r.idExterno()).isNull();
    }

    @Test
    @DisplayName("Si la cita nunca se pagó, no hay nada que devolver")
    void sinPago() {
        when(pagos.findFirstByCita_IdAndEstado(citaId, EstadoPago.VERIFICADO)).thenReturn(Optional.empty());

        assertThat(servicio("simulado").reembolsarDeposito(citaId, "cancelacion:" + citaId, null)).isEmpty();
        verify(reembolsos, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Idempotente: la misma clave no crea un segundo reembolso")
    void idempotente() {
        Reembolso existente = Reembolso.builder().id(UUID.randomUUID()).pago(pago).monto(new BigDecimal("30.00"))
                .estado(EstadoReembolso.REEMBOLSADO).idExterno("sim_rfd_x").build();
        when(reembolsos.findByPago_IdAndClaveIdempotencia(pago.getId(), "cancelacion:" + citaId))
                .thenReturn(Optional.of(existente));

        ReembolsoResponse r = servicio("simulado").reembolsarDeposito(citaId, "cancelacion:" + citaId, null).orElseThrow();

        assertThat(r.id()).isEqualTo(existente.getId());
        verify(reembolsos, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Solo el paciente o el odontólogo de la cita ven sus reembolsos (404 para otros)")
    void deCitaAjena() {
        Cita cita = Cita.builder().id(citaId).paciente(Usuario.builder().id(UUID.randomUUID()).build())
                .odontologo(PerfilOdontologo.builder().usuarioId(UUID.randomUUID()).build()).build();
        when(citas.findById(citaId)).thenReturn(Optional.of(cita));

        assertThatThrownBy(() -> servicio("simulado").deCita(UUID.randomUUID(), citaId))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }
}

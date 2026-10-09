package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.CitaDtos.CitaResponse;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.EstadoWebhookPago;
import com.odontosystem.OdontoSystem.entity.EventoPagoWebhook;
import com.odontosystem.OdontoSystem.entity.IntentoPago;
import com.odontosystem.OdontoSystem.entity.MetodoPago;
import com.odontosystem.OdontoSystem.entity.Pago;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.EventoPagoWebhookRepository;
import com.odontosystem.OdontoSystem.repository.IntentoPagoRepository;
import com.odontosystem.OdontoSystem.repository.PagoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PagoServiceTest {

    @Mock private CitaRepository citaRepository;
    @Mock private PagoRepository pagoRepository;
    @Mock private IntentoPagoRepository intentoRepository;
    @Mock private EventoPagoWebhookRepository eventoRepository;
    @Mock private CitaService citaService;

    private final Clock reloj = Clock.fixed(Instant.parse("2026-10-19T13:00:00Z"), ZoneOffset.UTC);
    private final PasarelaSimulada pasarela = new PasarelaSimulada("llave-de-prueba-de-32-bytes!!!!!".getBytes(StandardCharsets.UTF_8));
    private PagoService servicio;
    private Usuario paciente;
    private PerfilOdontologo perfil;

    @BeforeEach
    void setUp() {
        servicio = new PagoService(citaRepository, pagoRepository, intentoRepository, eventoRepository,
                citaService, pasarela, reloj, "simulado");
        paciente = Usuario.builder().id(UUID.randomUUID()).build();
        perfil = PerfilOdontologo.builder().usuarioId(UUID.randomUUID()).build();
    }

    private Cita cita(EstadoCita estado, int minutosDesdeReserva) {
        return Cita.builder().id(UUID.randomUUID()).paciente(paciente).odontologo(perfil).estado(estado)
                .montoDepositoRequerido(new BigDecimal("16.00"))
                .creadoEn(OffsetDateTime.now(reloj).minusMinutes(minutosDesdeReserva)).build();
    }

    private void guardadosDevuelvenLoMismo() {
        when(pagoRepository.saveAndFlush(any(Pago.class))).thenAnswer(inv -> {
            Pago p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        when(intentoRepository.saveAndFlush(any(IntentoPago.class))).thenAnswer(inv -> {
            IntentoPago i = inv.getArgument(0);
            if (i.getId() == null) i.setId(UUID.randomUUID());
            return i;
        });
        when(eventoRepository.saveAndFlush(any(EventoPagoWebhook.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    @Test
    @DisplayName("Pago OK: aviso firmado y PROCESADO, pago VERIFICADO y cita CONFIRMADA")
    void pagoConfirmaLaCita() {
        Cita cita = cita(EstadoCita.PENDIENTE_PAGO, 5);
        when(citaRepository.findById(cita.getId())).thenReturn(Optional.of(cita));
        guardadosDevuelvenLoMismo();
        when(citaService.cambiarEstado(eq(cita), eq(paciente.getId()), eq(EstadoCita.CONFIRMADA), isNull()))
                .thenReturn(mock(CitaResponse.class));

        var r = servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.YAPE);

        assertThat(r.estado()).isEqualTo(EstadoPago.VERIFICADO);
        assertThat(r.monto()).isEqualByComparingTo("16.00");
        assertThat(r.numeroOperacion()).startsWith("sim_chr_");
        ArgumentCaptor<EventoPagoWebhook> aviso = ArgumentCaptor.forClass(EventoPagoWebhook.class);
        verify(eventoRepository).saveAndFlush(aviso.capture());
        assertThat(aviso.getValue().isFirmaVerificada()).isTrue();
        assertThat(aviso.getValue().getEstado()).isEqualTo(EstadoWebhookPago.PROCESADO);
    }

    @Test
    @DisplayName("Odontólogo con confirmación manual: la cita queda PENDIENTE_CONFIRMACION")
    void confirmacionManual() {
        perfil.setRequiereConfirmacionManual(true);
        Cita cita = cita(EstadoCita.PENDIENTE_PAGO, 1);
        when(citaRepository.findById(cita.getId())).thenReturn(Optional.of(cita));
        guardadosDevuelvenLoMismo();
        when(citaService.cambiarEstado(any(), any(), eq(EstadoCita.PENDIENTE_CONFIRMACION), isNull()))
                .thenReturn(mock(CitaResponse.class));

        servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA);

        verify(citaService).cambiarEstado(any(), any(), eq(EstadoCita.PENDIENTE_CONFIRMACION), isNull());
    }

    @Test
    @DisplayName("Cita ya pagada o con el plazo vencido → 409; cita de otro paciente → 404")
    void validaciones() {
        Cita confirmada = cita(EstadoCita.CONFIRMADA, 1);
        when(citaRepository.findById(confirmada.getId())).thenReturn(Optional.of(confirmada));
        assertThatThrownBy(() -> servicio.pagarDeposito(paciente.getId(), confirmada.getId(), MetodoPago.YAPE))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));

        Cita vencida = cita(EstadoCita.PENDIENTE_PAGO, 20);
        when(citaRepository.findById(vencida.getId())).thenReturn(Optional.of(vencida));
        assertThatThrownBy(() -> servicio.pagarDeposito(paciente.getId(), vencida.getId(), MetodoPago.YAPE))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));

        assertThatThrownBy(() -> servicio.pagarDeposito(UUID.randomUUID(), vencida.getId(), MetodoPago.YAPE))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
        verify(pagoRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Si los pagos no están en modo simulado → 503")
    void modoDesactivado() {
        var sinPagos = new PagoService(citaRepository, pagoRepository, intentoRepository, eventoRepository,
                citaService, pasarela, reloj, "culqi");
        assertThatThrownBy(() -> sinPagos.pagarDeposito(paciente.getId(), UUID.randomUUID(), MetodoPago.YAPE))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(503));
    }

    @Test
    @DisplayName("La firma del aviso: válida tal cual, inválida si alguien cambia el monto")
    void firmaDelAviso() {
        var cobro = pasarela.cobrar(UUID.randomUUID(), new BigDecimal("16.00"), "YAPE");
        assertThat(pasarela.firmaValida(cobro.payload(), cobro.firma())).isTrue();
        assertThat(pasarela.firmaValida(cobro.payload().replace("16.00", "0.01"), cobro.firma())).isFalse();
        var otraPasarela = new PasarelaSimulada();
        assertThat(otraPasarela.firmaValida(cobro.payload(), cobro.firma())).isFalse();
    }
}

package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.CitaDtos.CitaResponse;
import com.odontosystem.OdontoSystem.citas.PagoService.ResultadoAviso;
import com.odontosystem.OdontoSystem.citas.PasarelaCulqi.Cargo;
import com.odontosystem.OdontoSystem.citas.PasarelaCulqi.CulqiNoDisponible;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoIntentoPago;
import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.EstadoWebhookPago;
import com.odontosystem.OdontoSystem.entity.EventoPagoWebhook;
import com.odontosystem.OdontoSystem.entity.IntentoPago;
import com.odontosystem.OdontoSystem.entity.MetodoPago;
import com.odontosystem.OdontoSystem.entity.Pago;
import com.odontosystem.OdontoSystem.entity.PasarelaPago;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RF13: pago del depósito con Culqi y avisos (webhooks) verificados contra la API de Culqi. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PagoServiceCulqiTest {

    @Mock private CitaRepository citaRepository;
    @Mock private PagoRepository pagoRepository;
    @Mock private IntentoPagoRepository intentoRepository;
    @Mock private EventoPagoWebhookRepository eventoRepository;
    @Mock private CitaService citaService;
    @Mock private PasarelaCulqi culqi;
    @Mock private ReembolsoService reembolsos;

    private final Clock reloj = Clock.fixed(Instant.parse("2026-10-19T13:00:00Z"), ZoneOffset.UTC);
    private PagoService servicio;
    private Usuario paciente;
    private Cita cita;

    @BeforeEach
    void setUp() {
        servicio = new PagoService(citaRepository, pagoRepository, intentoRepository, eventoRepository, citaService,
                new PasarelaSimulada(), culqi, reembolsos, reloj, "culqi");
        paciente = Usuario.builder().id(UUID.randomUUID()).correo("lucia@correo.pe").build();
        cita = Cita.builder().id(UUID.randomUUID()).paciente(paciente)
                .odontologo(PerfilOdontologo.builder().usuarioId(UUID.randomUUID()).build())
                .estado(EstadoCita.PENDIENTE_PAGO).montoDepositoRequerido(new BigDecimal("16.00"))
                .creadoEn(OffsetDateTime.now(reloj).minusMinutes(3)).build();

        when(culqi.configurada()).thenReturn(true);
        when(citaRepository.findById(cita.getId())).thenReturn(Optional.of(cita));
        when(pagoRepository.findFirstByCita_IdAndEstado(any(), any())).thenReturn(Optional.empty());
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
        when(citaService.cambiarEstado(any(), any(), any(), isNull())).thenReturn(mock(CitaResponse.class));
    }

    private static Cargo aprobado(String id, long centimos, Map<String, String> metadata) {
        return new Cargo(id, true, centimos, "PEN", "AUT0000", "Su compra ha sido exitosa.", metadata, "{\"id\":\"" + id + "\"}");
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    // ---------------------------------------------------------------- pagar

    @Test
    @DisplayName("Tarjeta aprobada: cobra 1600 céntimos, registra la confirmación verificada, pago VERIFICADO y cita CONFIRMADA")
    void pagoAprobado() {
        when(culqi.cobrar(eq("tkn_test_1"), eq(1600L), eq("lucia@correo.pe"), anyString(), anyMap()))
                .thenReturn(aprobado("chr_test_1", 1600, Map.of()));

        var r = servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA, "tkn_test_1");

        assertThat(r.estado()).isEqualTo(EstadoPago.VERIFICADO);
        assertThat(r.numeroOperacion()).isEqualTo("chr_test_1");
        assertThat(r.simulado()).isFalse();
        ArgumentCaptor<EventoPagoWebhook> aviso = ArgumentCaptor.forClass(EventoPagoWebhook.class);
        verify(eventoRepository).saveAndFlush(aviso.capture());
        assertThat(aviso.getValue().getEstado()).isEqualTo(EstadoWebhookPago.PROCESADO);
        assertThat(aviso.getValue().isFirmaVerificada()).isTrue();
        assertThat(aviso.getValue().getEventoExternoId()).isEqualTo("api:chr_test_1");
        verify(citaService).cambiarEstado(cita, paciente.getId(), EstadoCita.CONFIRMADA, null);
    }

    @Test
    @DisplayName("Tarjeta rechazada: pago RECHAZADO con el mensaje de Culqi; la cita sigue esperando pago")
    void pagoRechazado() {
        when(culqi.cobrar(anyString(), anyLong(), anyString(), anyString(), anyMap()))
                .thenReturn(new Cargo(null, false, 0, null, "insufficient_funds", "Fondos insuficientes.", Map.of(), "{}"));

        var r = servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA, "tkn_test_1");

        assertThat(r.estado()).isEqualTo(EstadoPago.RECHAZADO);
        assertThat(r.mensaje()).isEqualTo("Fondos insuficientes.");
        verify(citaService, never()).cambiarEstado(any(), any(), any(), any());
        verify(eventoRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Culqi no responde: el pago queda PENDIENTE (lo cierra el aviso) y se pide no volver a pagar")
    void culqiNoResponde() {
        when(culqi.cobrar(anyString(), anyLong(), anyString(), anyString(), anyMap()))
                .thenThrow(new CulqiNoDisponible("timeout", null));

        var r = servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.YAPE, "ype_test_1");

        assertThat(r.estado()).isEqualTo(EstadoPago.PENDIENTE);
        assertThat(r.mensaje()).contains("no vuelvas a pagar");
        verify(citaService, never()).cambiarEstado(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Validaciones: Plin aún no, falta el token, token de otro medio, o ya hay un pago en confirmación")
    void validaciones() {
        assertThatThrownBy(() -> servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.PLIN, "tkn_x"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
        assertThatThrownBy(() -> servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA, " "))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
        assertThatThrownBy(() -> servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA, "ype_test_1"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));

        when(pagoRepository.findFirstByCita_IdAndEstado(cita.getId(), EstadoPago.PENDIENTE))
                .thenReturn(Optional.of(Pago.builder().build()));
        assertThatThrownBy(() -> servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA, "tkn_test_1"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
        verify(culqi, never()).cobrar(anyString(), anyLong(), anyString(), anyString(), anyMap());
    }

    // ---------------------------------------------------------------- avisos (webhook)

    private IntentoPago intentoPendiente() {
        Pago pago = Pago.builder().id(UUID.randomUUID()).cita(cita).monto(new BigDecimal("16.00"))
                .metodoPago(MetodoPago.TARJETA).pasarelaPago(PasarelaPago.CULQI).estado(EstadoPago.PENDIENTE).build();
        IntentoPago intento = IntentoPago.builder().id(UUID.randomUUID()).pago(pago).monto(pago.getMonto())
                .pasarelaPago(PasarelaPago.CULQI).estado(EstadoIntentoPago.PENDIENTE).build();
        when(intentoRepository.findById(intento.getId())).thenReturn(Optional.of(intento));
        return intento;
    }

    private static String aviso(String eventoId, String cargoId) {
        return "{\"object\":\"event\",\"id\":\"" + eventoId + "\",\"type\":\"charge.creation.succeeded\","
                + "\"data\":\"{\\\"id\\\":\\\"" + cargoId + "\\\",\\\"amount\\\":1}\"}";
    }

    @Test
    @DisplayName("Aviso verificado consultando a Culqi: confirma el pago pendiente y la cita")
    void avisoConfirma() {
        IntentoPago intento = intentoPendiente();
        when(culqi.consultarCargo("chr_test_7"))
                .thenReturn(Optional.of(aprobado("chr_test_7", 1600, Map.of("intento_id", intento.getId().toString()))));

        assertThat(servicio.procesarAvisoCulqi(aviso("evt_1", "chr_test_7"))).isEqualTo(ResultadoAviso.CONFIRMADO);

        assertThat(intento.getPago().getEstado()).isEqualTo(EstadoPago.VERIFICADO);
        assertThat(intento.getEstado()).isEqualTo(EstadoIntentoPago.APROBADO);
        verify(citaService).cambiarEstado(cita, paciente.getId(), EstadoCita.CONFIRMADA, null);
    }

    @Test
    @DisplayName("El monto del aviso no se cree: si Culqi dice otro monto, no se confirma")
    void avisoConOtroMonto() {
        IntentoPago intento = intentoPendiente();
        when(culqi.consultarCargo("chr_test_7"))
                .thenReturn(Optional.of(aprobado("chr_test_7", 100, Map.of("intento_id", intento.getId().toString()))));

        assertThat(servicio.procesarAvisoCulqi(aviso("evt_2", "chr_test_7"))).isEqualTo(ResultadoAviso.RECHAZADO);

        assertThat(intento.getPago().getEstado()).isEqualTo(EstadoPago.PENDIENTE);
        verify(citaService, never()).cambiarEstado(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Aviso repetido o falso: no se procesa dos veces; un cargo que Culqi no conoce se ignora")
    void avisoRepetidoOFalso() {
        when(eventoRepository.existsByPasarelaPagoAndEventoExternoId(PasarelaPago.CULQI, "evt_dup")).thenReturn(true);
        assertThat(servicio.procesarAvisoCulqi(aviso("evt_dup", "chr_test_7"))).isEqualTo(ResultadoAviso.YA_PROCESADO);
        verify(culqi, never()).consultarCargo(anyString());

        when(culqi.consultarCargo("chr_inventado")).thenReturn(Optional.empty());
        assertThat(servicio.procesarAvisoCulqi(aviso("evt_3", "chr_inventado"))).isEqualTo(ResultadoAviso.IGNORADO);
        assertThat(servicio.procesarAvisoCulqi("no es json")).isEqualTo(ResultadoAviso.IGNORADO);
    }

    @Test
    @DisplayName("Si el pago llega cuando la reserva ya venció, se confirma y se devuelve el depósito")
    void avisoTardio() {
        IntentoPago intento = intentoPendiente();
        cita.setEstado(EstadoCita.CANCELADA_SIN_REEMBOLSO);
        when(culqi.consultarCargo("chr_test_8"))
                .thenReturn(Optional.of(aprobado("chr_test_8", 1600, Map.of("intento_id", intento.getId().toString()))));

        assertThat(servicio.procesarAvisoCulqi(aviso("evt_4", "chr_test_8"))).isEqualTo(ResultadoAviso.CONFIRMADO_Y_DEVUELTO);

        verify(reembolsos).reembolsarDeposito(eq(cita.getId()), eq("pago-tardio:" + intento.getPago().getId()), anyString());
        verify(citaService, never()).cambiarEstado(any(), any(), any(), any());
    }
}

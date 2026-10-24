package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.CitaDtos.CitaResponse;
import com.odontosystem.OdontoSystem.citas.PagoService.ResultadoAviso;
import com.odontosystem.OdontoSystem.citas.PasarelaMercadoPago.MercadoPagoNoDisponible;
import com.odontosystem.OdontoSystem.citas.PasarelaMercadoPago.Orden;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pago del depósito con Mercado Pago (API de Orders) y avisos verificados contra su API. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PagoServiceMercadoPagoTest {

    private static final String ORDEN = "ORD01JS2V6CM8KJ0EC4H502TGK1WP";

    @Mock private CitaRepository citaRepository;
    @Mock private PagoRepository pagoRepository;
    @Mock private IntentoPagoRepository intentoRepository;
    @Mock private EventoPagoWebhookRepository eventoRepository;
    @Mock private CitaService citaService;
    @Mock private PasarelaMercadoPago mp;
    @Mock private ReembolsoService reembolsos;

    private final Clock reloj = Clock.fixed(Instant.parse("2026-10-21T13:00:00Z"), ZoneOffset.UTC);
    private PagoService servicio;
    private Usuario paciente;
    private Cita cita;

    @BeforeEach
    void setUp() {
        servicio = new PagoService(citaRepository, pagoRepository, intentoRepository, eventoRepository, citaService,
                new PasarelaSimulada(), mp, reembolsos, reloj, "mercadopago");
        paciente = Usuario.builder().id(UUID.randomUUID()).correo("lucia@correo.pe").build();
        cita = Cita.builder().id(UUID.randomUUID()).paciente(paciente)
                .odontologo(PerfilOdontologo.builder().usuarioId(UUID.randomUUID()).build())
                .estado(EstadoCita.PENDIENTE_PAGO).montoDepositoRequerido(new BigDecimal("16.00"))
                .creadoEn(OffsetDateTime.now(reloj).minusMinutes(3)).build();

        when(mp.configurada()).thenReturn(true);
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

    private static Orden aprobada(String referencia) {
        return new Orden(ORDEN, "processed", "accredited", 1600, referencia, "Pago aprobado", "{\"id\":\"" + ORDEN + "\"}");
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    @Test
    @DisplayName("Tarjeta aprobada: pasarela MERCADO_PAGO, confirmación verificada, pago VERIFICADO y cita CONFIRMADA")
    void tarjetaAprobada() {
        when(mp.cobrar(eq("tok1"), eq("visa"), eq(1600L), eq("lucia@correo.pe"), anyString(), anyString()))
                .thenReturn(aprobada("x"));

        var r = servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA, "tok1", "VISA");

        assertThat(r.estado()).isEqualTo(EstadoPago.VERIFICADO);
        assertThat(r.numeroOperacion()).isEqualTo(ORDEN);
        ArgumentCaptor<Pago> pago = ArgumentCaptor.forClass(Pago.class);
        verify(pagoRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(pago.capture());
        assertThat(pago.getValue().getPasarelaPago()).isEqualTo(PasarelaPago.MERCADO_PAGO);
        ArgumentCaptor<EventoPagoWebhook> aviso = ArgumentCaptor.forClass(EventoPagoWebhook.class);
        verify(eventoRepository).saveAndFlush(aviso.capture());
        assertThat(aviso.getValue().getPasarelaPago()).isEqualTo(PasarelaPago.MERCADO_PAGO);
        assertThat(aviso.getValue().getEstado()).isEqualTo(EstadoWebhookPago.PROCESADO);
        assertThat(aviso.getValue().isFirmaVerificada()).isTrue();
        assertThat(aviso.getValue().getEventoExternoId()).isEqualTo("api:" + ORDEN);
        verify(citaService).cambiarEstado(cita, paciente.getId(), EstadoCita.CONFIRMADA, null);
    }

    @Test
    @DisplayName("Yape: se cobra con el medio yape aunque el frontend no lo mande")
    void yape() {
        when(mp.cobrar(eq("ytok"), eq("yape"), eq(1600L), anyString(), anyString(), anyString())).thenReturn(aprobada("x"));

        var r = servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.YAPE, "ytok", null);

        assertThat(r.estado()).isEqualTo(EstadoPago.VERIFICADO);
    }

    @Test
    @DisplayName("Rechazada: pago RECHAZADO con el mensaje; la cita sigue esperando pago")
    void rechazada() {
        when(mp.cobrar(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new Orden("ORD02", "failed", "insufficient_amount", 0, "x", "Fondos insuficientes.", "{}"));

        var r = servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA, "tok1", "master");

        assertThat(r.estado()).isEqualTo(EstadoPago.RECHAZADO);
        assertThat(r.mensaje()).isEqualTo("Fondos insuficientes.");
        verify(citaService, never()).cambiarEstado(any(), any(), any(), any());
    }

    @Test
    @DisplayName("En curso (processing) o Mercado Pago caído: el pago queda PENDIENTE, sin confirmar la cita")
    void pendiente() {
        when(mp.cobrar(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new Orden(ORDEN, "processing", "in_process", 0, "x", null, "{}"))
                .thenThrow(new MercadoPagoNoDisponible("timeout", null));

        assertThat(servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA, "t", "visa").estado())
                .isEqualTo(EstadoPago.PENDIENTE);
        assertThat(servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA, "t", "visa").estado())
                .isEqualTo(EstadoPago.PENDIENTE);
        verify(citaService, never()).cambiarEstado(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Validaciones: sin credenciales 503; Plin, sin token o tarjeta sin medio 400")
    void validaciones() {
        assertThatThrownBy(() -> servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.PLIN, "t", null))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
        assertThatThrownBy(() -> servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA, " ", "visa"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
        assertThatThrownBy(() -> servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA, "t", null))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
        assertThatThrownBy(() -> servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.YAPE, "t", "visa"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
        when(mp.configurada()).thenReturn(false);
        assertThatThrownBy(() -> servicio.pagarDeposito(paciente.getId(), cita.getId(), MetodoPago.TARJETA, "t", "visa"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(503));
    }

    // ---------------------------------------------------------------- avisos

    private IntentoPago intentoPendiente() {
        Pago pago = Pago.builder().id(UUID.randomUUID()).cita(cita).metodoPago(MetodoPago.TARJETA)
                .pasarelaPago(PasarelaPago.MERCADO_PAGO).monto(new BigDecimal("16.00")).estado(EstadoPago.PENDIENTE).build();
        IntentoPago intento = IntentoPago.builder().id(UUID.randomUUID()).pago(pago).pasarelaPago(PasarelaPago.MERCADO_PAGO)
                .monto(new BigDecimal("16.00")).estado(EstadoIntentoPago.PENDIENTE).build();
        when(intentoRepository.findById(intento.getId())).thenReturn(Optional.of(intento));
        return intento;
    }

    private static String aviso(String id) {
        return "{\"action\":\"order.processed\",\"type\":\"order\",\"data\":{\"id\":\"" + id + "\",\"status\":\"processed\"}}";
    }

    @Test
    @DisplayName("Aviso de orden cobrada: se consulta la orden a Mercado Pago, se confirma el pago y la cita")
    void avisoConfirma() {
        IntentoPago intento = intentoPendiente();
        when(mp.consultarOrden(ORDEN)).thenReturn(Optional.of(aprobada(intento.getId().toString())));

        assertThat(servicio.procesarAvisoMercadoPago(aviso(ORDEN), null)).isEqualTo(ResultadoAviso.CONFIRMADO);

        assertThat(intento.getPago().getEstado()).isEqualTo(EstadoPago.VERIFICADO);
        assertThat(intento.getEstado()).isEqualTo(EstadoIntentoPago.APROBADO);
        verify(citaService).cambiarEstado(cita, paciente.getId(), EstadoCita.CONFIRMADA, null);
    }

    @Test
    @DisplayName("Aviso con el id solo en la URL (data.id) también funciona")
    void avisoIdEnUrl() {
        IntentoPago intento = intentoPendiente();
        when(mp.consultarOrden(ORDEN)).thenReturn(Optional.of(aprobada(intento.getId().toString())));

        assertThat(servicio.procesarAvisoMercadoPago("", ORDEN)).isEqualTo(ResultadoAviso.CONFIRMADO);
    }

    @Test
    @DisplayName("Aviso repetido, de otro tema, con id raro o de una orden ajena: no confirma nada")
    void avisosIgnorados() {
        when(eventoRepository.existsByPasarelaPagoAndEventoExternoId(PasarelaPago.MERCADO_PAGO, ORDEN + ":processed:accredited"))
                .thenReturn(true);
        when(mp.consultarOrden(ORDEN)).thenReturn(Optional.of(aprobada(UUID.randomUUID().toString())));

        assertThat(servicio.procesarAvisoMercadoPago(aviso(ORDEN), null)).isEqualTo(ResultadoAviso.YA_PROCESADO);
        assertThat(servicio.procesarAvisoMercadoPago("{\"type\":\"payment\",\"data\":{\"id\":\"123\"}}", null))
                .isEqualTo(ResultadoAviso.IGNORADO);
        assertThat(servicio.procesarAvisoMercadoPago(aviso("x' or 1=1"), null)).isEqualTo(ResultadoAviso.IGNORADO);

        when(eventoRepository.existsByPasarelaPagoAndEventoExternoId(any(), anyString())).thenReturn(false);
        assertThat(servicio.procesarAvisoMercadoPago(aviso(ORDEN), null)).isEqualTo(ResultadoAviso.IGNORADO);
        verify(citaService, never()).cambiarEstado(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Aviso de orden rechazada: pago RECHAZADO")
    void avisoRechazo() {
        IntentoPago intento = intentoPendiente();
        when(mp.consultarOrden(ORDEN)).thenReturn(Optional.of(
                new Orden(ORDEN, "failed", "rejected_by_issuer", 0, intento.getId().toString(), "x", "{}")));

        assertThat(servicio.procesarAvisoMercadoPago(aviso(ORDEN), null)).isEqualTo(ResultadoAviso.RECHAZADO);
        assertThat(intento.getPago().getEstado()).isEqualTo(EstadoPago.RECHAZADO);
    }

    @Test
    @DisplayName("Cobro que llega con la reserva vencida: se verifica y se devuelve el depósito")
    void avisoTardio() {
        IntentoPago intento = intentoPendiente();
        cita.setEstado(EstadoCita.CANCELADA_SIN_REEMBOLSO);
        when(mp.consultarOrden(ORDEN)).thenReturn(Optional.of(aprobada(intento.getId().toString())));

        assertThat(servicio.procesarAvisoMercadoPago(aviso(ORDEN), null)).isEqualTo(ResultadoAviso.CONFIRMADO_Y_DEVUELTO);
        verify(reembolsos).reembolsarDeposito(eq(cita.getId()), anyString(), anyString());
    }
}

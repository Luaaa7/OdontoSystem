package com.odontosystem.OdontoSystem.notificaciones;

import com.odontosystem.OdontoSystem.citas.CitaCambioEstado;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoNotificacion;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.RecordatorioCita;
import com.odontosystem.OdontoSystem.entity.RegistroNotificacion;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.entity.TipoNotificacion;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.RecordatorioCitaRepository;
import com.odontosystem.OdontoSystem.repository.RegistroNotificacionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recordatorios y avisos por correo. Las plantillas son las reales (Thymeleaf), el envío se simula.
 * "Ahora" es el domingo 11/10/2026 a las 10:00 en Ica.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificacionServiceTest {

    private static final ZoneOffset MENOS_5 = ZoneOffset.ofHours(-5);
    private static final OffsetDateTime AHORA = OffsetDateTime.of(2026, 10, 11, 10, 0, 0, 0, MENOS_5);
    private static final Clock RELOJ = Clock.fixed(AHORA.toInstant(), ZoneId.of("America/Lima"));

    @Mock private RecordatorioCitaRepository recordatorios;
    @Mock private RegistroNotificacionRepository registros;
    @Mock private CitaRepository citas;
    @Mock private EnviadorCorreo enviador;
    @Mock private PlatformTransactionManager transacciones;

    private NotificacionService servicio;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine motor = new SpringTemplateEngine();
        motor.setTemplateResolver(resolver);
        servicio = new NotificacionService(recordatorios, registros, citas, enviador, new PlantillasCorreo(motor),
                RELOJ, transacciones);
        when(enviador.activo()).thenReturn(true);
    }

    private static Cita cita(EstadoCita estado, OffsetDateTime inicio) {
        Usuario paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Lucía Ramos Peña")
                .correo("lucia@correo.pe").build();
        Usuario usuarioOdontologo = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Dra. Ana Torres").build();
        PerfilOdontologo odontologo = PerfilOdontologo.builder().usuarioId(usuarioOdontologo.getId())
                .usuario(usuarioOdontologo).nombreConsultorio("Sonrisas Parcona")
                .direccionConsultorio("Av. Principal 123").distritoConsultorio("Parcona").build();
        Servicio servicio = Servicio.builder().id(UUID.randomUUID()).titulo("Resina estética").build();
        return Cita.builder().id(UUID.randomUUID()).paciente(paciente).odontologo(odontologo).servicio(servicio)
                .inicioProgramado(inicio).finProgramado(inicio.plusMinutes(30)).estado(estado)
                .precioServicio(new BigDecimal("150")).montoDepositoRequerido(new BigDecimal("30")).build();
    }

    private RecordatorioCita pendiente(Cita cita, OffsetDateTime programado) {
        RecordatorioCita r = RecordatorioCita.builder().id(UUID.randomUUID()).cita(cita).programadoPara(programado).build();
        when(recordatorios.vencidos(eq(EstadoNotificacion.PENDIENTE), any(), any())).thenReturn(List.of(r.getId()));
        when(recordatorios.conDetalle(r.getId())).thenReturn(Optional.of(r));
        when(recordatorios.cambiarEstadoSi(eq(r.getId()), any(), any(), any())).thenReturn(1);
        return r;
    }

    // ------------------------------------------------------------------ programar

    @Test
    @DisplayName("Cita en 30 horas: programa los recordatorios de 24 h y de 2 h antes")
    void programaLosDos() {
        Cita c = cita(EstadoCita.CONFIRMADA, AHORA.plusHours(30));
        when(citas.sinRecordatorios(eq(EstadoCita.CONFIRMADA), any(), any())).thenReturn(List.of(c));

        assertThat(servicio.programarRecordatorios()).isEqualTo(2);

        ArgumentCaptor<RecordatorioCita> captor = ArgumentCaptor.forClass(RecordatorioCita.class);
        verify(recordatorios, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(RecordatorioCita::getProgramadoPara)
                .containsExactly(AHORA.plusHours(6), AHORA.plusHours(28));
    }

    @Test
    @DisplayName("Cita en 10 horas: el de 24 h ya pasó, solo programa el de 2 h; en 1 hora, ninguno")
    void soloLosQueSiguenEnElFuturo() {
        when(citas.sinRecordatorios(eq(EstadoCita.CONFIRMADA), any(), any()))
                .thenReturn(List.of(cita(EstadoCita.CONFIRMADA, AHORA.plusHours(10)), cita(EstadoCita.CONFIRMADA, AHORA.plusHours(1))));

        assertThat(servicio.programarRecordatorios()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ enviar

    @Test
    @DisplayName("Recordatorio vencido de una cita confirmada: lo envía al paciente y lo registra")
    void enviaElRecordatorio() {
        Cita c = cita(EstadoCita.CONFIRMADA, AHORA.plusHours(24));
        RecordatorioCita r = pendiente(c, AHORA);

        assertThat(servicio.enviarPendientes()).isEqualTo(1);

        ArgumentCaptor<String> asunto = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(enviador).enviar(eq("lucia@correo.pe"), eq("Lucía Ramos Peña"), asunto.capture(), html.capture());
        assertThat(asunto.getValue()).isEqualTo("Recordatorio: tu cita de mañana a las 10:00");
        assertThat(html.getValue()).contains("Lucía").contains("Resina estética")
                .contains("lunes 12 de octubre").contains("Dra. Ana Torres").contains("Av. Principal 123").contains(", Parcona");
        verify(recordatorios).cambiarEstadoSi(r.getId(), EstadoNotificacion.PENDIENTE, EstadoNotificacion.ENVIADA, AHORA);
        ArgumentCaptor<RegistroNotificacion> registro = ArgumentCaptor.forClass(RegistroNotificacion.class);
        verify(registros).save(registro.capture());
        assertThat(registro.getValue().getTipo()).isEqualTo(TipoNotificacion.RECORDATORIO_CITA);
        assertThat(registro.getValue().getEstado()).isEqualTo(EstadoNotificacion.ENVIADA);
    }

    @Test
    @DisplayName("Si la cita se canceló, el recordatorio se borra y no sale ningún correo")
    void citaCanceladaNoSeRecuerda() {
        RecordatorioCita r = pendiente(cita(EstadoCita.CANCELADA_CON_REEMBOLSO, AHORA.plusHours(2)), AHORA);

        assertThat(servicio.enviarPendientes()).isZero();

        verify(recordatorios).delete(r);
        verify(enviador, never()).enviar(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Si otra instancia del backend ya lo tomó, no se envía dos veces")
    void otraInstanciaLoTomo() {
        RecordatorioCita r = pendiente(cita(EstadoCita.CONFIRMADA, AHORA.plusHours(2)), AHORA);
        when(recordatorios.cambiarEstadoSi(r.getId(), EstadoNotificacion.PENDIENTE, EstadoNotificacion.ENVIADA, AHORA))
                .thenReturn(0);

        assertThat(servicio.enviarPendientes()).isZero();
        verify(enviador, never()).enviar(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Si el proveedor falla, el recordatorio queda FALLIDA y se registra el intento")
    void proveedorFalla() {
        RecordatorioCita r = pendiente(cita(EstadoCita.CONFIRMADA, AHORA.plusHours(2)), AHORA);
        doThrow(new IllegalStateException("sin red")).when(enviador).enviar(anyString(), anyString(), anyString(), anyString());

        assertThat(servicio.enviarPendientes()).isZero();

        verify(recordatorios).cambiarEstadoSi(eq(r.getId()), eq(EstadoNotificacion.ENVIADA), eq(EstadoNotificacion.FALLIDA), isNull());
        ArgumentCaptor<RegistroNotificacion> registro = ArgumentCaptor.forClass(RegistroNotificacion.class);
        verify(registros).save(registro.capture());
        assertThat(registro.getValue().getEstado()).isEqualTo(EstadoNotificacion.FALLIDA);
    }

    @Test
    @DisplayName("Sin proveedor de correo no toca nada: los recordatorios quedan pendientes para otra instancia")
    void sinProveedor() {
        when(enviador.activo()).thenReturn(false);

        assertThat(servicio.enviarPendientes()).isZero();
        verify(recordatorios, never()).vencidos(any(), any(), any());
    }

    // ------------------------------------------------------------------ avisos inmediatos

    @Test
    @DisplayName("Cita confirmada (pago verificado): avisa al paciente con fecha, hora y depósito")
    void avisoConfirmada() {
        Cita c = cita(EstadoCita.CONFIRMADA, AHORA.plusDays(1));
        when(citas.conDetalle(c.getId())).thenReturn(Optional.of(c));

        servicio.avisarCambioDeEstado(new CitaCambioEstado(c.getId(), EstadoCita.PENDIENTE_PAGO, EstadoCita.CONFIRMADA));

        ArgumentCaptor<String> asunto = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(enviador).enviar(eq("lucia@correo.pe"), any(), asunto.capture(), html.capture());
        assertThat(asunto.getValue()).startsWith("Tu cita está confirmada");
        assertThat(html.getValue()).contains("confirmada").contains("S/ 30.00").contains("S/ 150.00");
    }

    @Test
    @DisplayName("Cancelada con reembolso: el correo lo dice; vencida sin pago: dice que la reserva se liberó")
    void avisosDeCancelacion() {
        Cita conReembolso = cita(EstadoCita.CANCELADA_CON_REEMBOLSO, AHORA.plusDays(2));
        Cita sinPago = cita(EstadoCita.CANCELADA_SIN_REEMBOLSO, AHORA.plusDays(2));
        sinPago.setMotivoCancelacion("No se pagó el depósito a tiempo");
        when(citas.conDetalle(conReembolso.getId())).thenReturn(Optional.of(conReembolso));
        when(citas.conDetalle(sinPago.getId())).thenReturn(Optional.of(sinPago));

        servicio.avisarCambioDeEstado(new CitaCambioEstado(conReembolso.getId(), EstadoCita.CONFIRMADA, EstadoCita.CANCELADA_CON_REEMBOLSO));
        servicio.avisarCambioDeEstado(new CitaCambioEstado(sinPago.getId(), EstadoCita.PENDIENTE_PAGO, EstadoCita.CANCELADA_SIN_REEMBOLSO));

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(enviador, times(2)).enviar(any(), any(), any(), html.capture());
        assertThat(html.getAllValues().get(0)).contains("fue cancelada").contains("Se te devolverá el depósito");
        assertThat(html.getAllValues().get(1)).contains("se liberó").contains("No se pagó el depósito a tiempo")
                .doesNotContain("no es reembolsable");
    }

    @Test
    @DisplayName("Los demás cambios de estado (por ejemplo, completada) no mandan correo")
    void otrosEstadosNoAvisan() {
        Cita c = cita(EstadoCita.COMPLETADA, AHORA.minusHours(1));
        when(citas.conDetalle(c.getId())).thenReturn(Optional.of(c));

        servicio.avisarCambioDeEstado(new CitaCambioEstado(c.getId(), EstadoCita.CONFIRMADA, EstadoCita.COMPLETADA));

        verify(enviador, never()).enviar(any(), any(), any(), any());
    }
}

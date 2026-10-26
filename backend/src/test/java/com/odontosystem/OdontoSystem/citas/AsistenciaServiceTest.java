package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.AsistenciaService.AsistenciaRequest;
import com.odontosystem.OdontoSystem.citas.AsistenciaService.AsistenciaResponse;
import com.odontosystem.OdontoSystem.citas.AsistenciaService.RespuestaReporteRequest;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoReporteAusencia;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.ReporteAusencia;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.reclamos.ReclamoService;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.HistoriaClinicaRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ReporteAusenciaRepository;
import com.odontosystem.OdontoSystem.usuarios.ReputacionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reloj fijo: lunes 19/10/2026, 10:30 en Lima. Consultorio en la Plaza de Armas de Ica. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsistenciaServiceTest {

    static final ZoneOffset LIMA = ZoneOffset.ofHours(-5);
    static final OffsetDateTime AHORA = OffsetDateTime.of(2026, 10, 19, 10, 30, 0, 0, LIMA);
    static final double LAT = -14.0639;
    static final double LON = -75.7292;

    @Mock private CitaRepository citas;
    @Mock private ReporteAusenciaRepository reportes;
    @Mock private HistoriaClinicaRepository historias;
    @Mock private PerfilOdontologoRepository perfiles;
    @Mock private CitaService citaService;
    @Mock private ReputacionService reputacion;
    @Mock private ReembolsoService reembolsos;
    @Mock private ReclamoService reclamos;
    @Mock private ApplicationEventPublisher eventos;

    private AsistenciaService servicio;
    private Usuario paciente;
    private PerfilOdontologo perfil;

    @BeforeEach
    void setUp() {
        servicio = new AsistenciaService(citas, reportes, historias, perfiles, citaService, reputacion, reembolsos,
                reclamos, eventos, Clock.fixed(AHORA.toInstant(), ZoneOffset.UTC));
        paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Lucía Ramos").rol(RolUsuario.PACIENTE).build();
        UUID odontologoId = UUID.randomUUID();
        perfil = PerfilOdontologo.builder().usuarioId(odontologoId)
                .usuario(Usuario.builder().id(odontologoId).nombreCompleto("Dra. Ana").build())
                .latitudConsultorio(BigDecimal.valueOf(LAT)).longitudConsultorio(BigDecimal.valueOf(LON)).build();
        when(reportes.saveAndFlush(any(ReporteAusencia.class))).thenAnswer(i -> {
            ReporteAusencia r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
    }

    /** Cita de 1 hora que empezó a las 10:00 (hace 30 min). */
    private Cita citaDeHoy(EstadoCita estado) {
        return cita(estado, AHORA.minusMinutes(30));
    }

    private Cita cita(EstadoCita estado, OffsetDateTime inicio) {
        Cita c = Cita.builder().id(UUID.randomUUID()).paciente(paciente).odontologo(perfil).estado(estado)
                .servicio(Servicio.builder().titulo("Limpieza").build())
                .inicioProgramado(inicio).finProgramado(inicio.plusHours(1)).build();
        when(citas.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    @Test
    @DisplayName("\"Sí me atendieron\": la cita queda COMPLETADA y se le quita un strike al paciente")
    void meAtendieron() {
        Cita c = citaDeHoy(EstadoCita.CONFIRMADA);
        servicio.responderPaciente(paciente.getId(), c.getId(), new AsistenciaRequest(true, null, null));
        verify(citaService).cambiarEstado(c, paciente.getId(), EstadoCita.COMPLETADA, null);
        verify(reputacion).quitarStrike(paciente.getId());
    }

    @Test
    @DisplayName("Antes de 15 min de la hora de la cita, o pasado el plazo de 72 h → 409")
    void fueraDePlazo() {
        Cita pronto = cita(EstadoCita.CONFIRMADA, AHORA.minusMinutes(5));
        assertThatThrownBy(() -> servicio.responderPaciente(paciente.getId(), pronto.getId(),
                new AsistenciaRequest(false, LAT, LON))).satisfies(e -> assertThat(estado(e)).isEqualTo(409));
        Cita vieja = cita(EstadoCita.CONFIRMADA, AHORA.minusDays(4));
        assertThatThrownBy(() -> servicio.responderPaciente(paciente.getId(), vieja.getId(),
                new AsistenciaRequest(false, null, null))).satisfies(e -> assertThat(estado(e)).isEqualTo(409));
    }

    @Test
    @DisplayName("Reportar \"no llegó\" durante la cita exige estar a menos de 200 m del consultorio")
    void ubicacion() {
        Cita c = citaDeHoy(EstadoCita.CONFIRMADA);
        assertThatThrownBy(() -> servicio.responderPaciente(paciente.getId(), c.getId(),
                new AsistenciaRequest(false, null, null))).satisfies(e -> assertThat(estado(e)).isEqualTo(400));
        // ~1,1 km al norte
        assertThatThrownBy(() -> servicio.responderPaciente(paciente.getId(), c.getId(),
                new AsistenciaRequest(false, LAT + 0.01, LON))).satisfies(e -> assertThat(estado(e)).isEqualTo(409));
        verify(reportes, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Reporte en el consultorio: queda PENDIENTE (disputa) y se avisa al odontólogo; aún no hay reembolso")
    void reporteAbreDisputa() {
        Cita c = citaDeHoy(EstadoCita.CONFIRMADA);
        AsistenciaResponse r = servicio.responderPaciente(paciente.getId(), c.getId(),
                new AsistenciaRequest(false, LAT + 0.0005, LON)); // ~55 m

        assertThat(r.reporte()).isEqualTo(EstadoReporteAusencia.PENDIENTE);
        ArgumentCaptor<ReporteAusencia> guardado = ArgumentCaptor.forClass(ReporteAusencia.class);
        verify(reportes).saveAndFlush(guardado.capture());
        assertThat(guardado.getValue().getDistanciaMetros()).isBetween(40, 70);
        verify(eventos).publishEvent(any(ReporteAusenciaCreado.class));
        verify(reembolsos, never()).reembolsarDeposito(any(), any(), any());
    }

    @Test
    @DisplayName("Paciente con otro reporte en 90 días: el nuevo va directo a reclamo")
    void reporteRepetido() {
        Cita c = citaDeHoy(EstadoCita.CONFIRMADA);
        when(reportes.countByCita_Paciente_IdAndCreadoEnAfter(eq(paciente.getId()), any())).thenReturn(1L);
        AsistenciaResponse r = servicio.responderPaciente(paciente.getId(), c.getId(), new AsistenciaRequest(false, LAT, LON));
        assertThat(r.reporte()).isEqualTo(EstadoReporteAusencia.EN_RECLAMO);
        verify(reclamos).registrar(eq(paciente.getId()), any());
        verify(eventos, never()).publishEvent(any(ReporteAusenciaCreado.class));
    }

    private ReporteAusencia pendiente(Cita c, OffsetDateTime creado) {
        ReporteAusencia r = ReporteAusencia.builder().id(UUID.randomUUID()).cita(c).creadoEn(creado).build();
        when(reportes.conDetalle(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    @DisplayName("El odontólogo admite la falta: reembolso, cita cancelada con reembolso y, con 3 faltas, suspensión")
    void admiteFalta() {
        Cita c = citaDeHoy(EstadoCita.CONFIRMADA);
        ReporteAusencia r = pendiente(c, AHORA.minusMinutes(5));
        when(reportes.countByCita_Odontologo_UsuarioIdAndEstadoAndResueltoEnAfter(eq(perfil.getUsuarioId()),
                eq(EstadoReporteAusencia.CONFIRMADO), any())).thenReturn(3L);

        servicio.responderOdontologo(perfil.getUsuarioId(), r.getId(), new RespuestaReporteRequest(false, "Tuve una emergencia"));

        assertThat(r.getEstado()).isEqualTo(EstadoReporteAusencia.CONFIRMADO);
        verify(reembolsos).reembolsarDeposito(eq(c.getId()), eq("ausencia:" + c.getId()), anyString());
        verify(citaService).cambiarEstado(eq(c), isNull(), eq(EstadoCita.CANCELADA_CON_REEMBOLSO), anyString());
        assertThat(perfil.getSuspendidoHasta()).isEqualTo(AHORA.plusDays(30).atZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime());
        verify(perfiles).save(perfil);
    }

    @Test
    @DisplayName("El odontólogo dice que sí atendió y tiene la historia clínica: reporte rechazado y strike al paciente")
    void pruebaConHistoria() {
        Cita c = citaDeHoy(EstadoCita.COMPLETADA);
        ReporteAusencia r = pendiente(c, AHORA.minusMinutes(5));
        when(historias.existsByCita_Id(c.getId())).thenReturn(true);

        servicio.responderOdontologo(perfil.getUsuarioId(), r.getId(), new RespuestaReporteRequest(true, null));

        assertThat(r.getEstado()).isEqualTo(EstadoReporteAusencia.RECHAZADO);
        verify(reputacion).sumarStrike(paciente);
        verify(reembolsos, never()).reembolsarDeposito(any(), any(), any());
    }

    @Test
    @DisplayName("Dice que atendió pero sin historia: sigue PENDIENTE y la cita queda COMPLETADA para poder registrarla")
    void sinPruebaTodavia() {
        Cita c = citaDeHoy(EstadoCita.CONFIRMADA);
        ReporteAusencia r = pendiente(c, AHORA.minusMinutes(5));

        servicio.responderOdontologo(perfil.getUsuarioId(), r.getId(), new RespuestaReporteRequest(true, "Sí vino"));

        assertThat(r.getEstado()).isEqualTo(EstadoReporteAusencia.PENDIENTE);
        assertThat(r.getRespuestaOdontologo()).isEqualTo("Sí vino");
        verify(citaService).cambiarEstado(c, perfil.getUsuarioId(), EstadoCita.COMPLETADA, null);
        verify(reputacion, never()).sumarStrike(any());
    }

    @Test
    @DisplayName("Pasadas 48 h: sin respuesta gana el paciente; con respuesta sin prueba pasa a reclamo")
    void vencidos() {
        ReporteAusencia silencio = pendiente(cita(EstadoCita.CONFIRMADA, AHORA.minusDays(3)), AHORA.minusHours(50));
        ReporteAusencia sinPrueba = pendiente(cita(EstadoCita.COMPLETADA, AHORA.minusDays(3)), AHORA.minusHours(50));
        sinPrueba.setRespuestaOdontologo("Sí vino");
        when(reportes.enEstadoCreadosAntesDe(eq(EstadoReporteAusencia.PENDIENTE), any()))
                .thenReturn(List.of(silencio, sinPrueba));
        when(citas.terminadasSinReporte(eq(EstadoCita.CONFIRMADA), any())).thenReturn(List.of());

        AsistenciaService.ResultadoTarea t = servicio.procesarVencidos();

        assertThat(t.confirmados()).isEqualTo(1);
        assertThat(t.enReclamo()).isEqualTo(1);
        assertThat(silencio.getEstado()).isEqualTo(EstadoReporteAusencia.CONFIRMADO);
        assertThat(sinPrueba.getEstado()).isEqualTo(EstadoReporteAusencia.EN_RECLAMO);
        verify(reclamos).registrar(eq(paciente.getId()), any());
    }

    @Test
    @DisplayName("Cita confirmada que nadie cerró en 72 h: se da por atendida")
    void cierreAutomatico() {
        Cita vieja = cita(EstadoCita.CONFIRMADA, AHORA.minusDays(4));
        when(reportes.enEstadoCreadosAntesDe(any(), any())).thenReturn(List.of());
        when(citas.terminadasSinReporte(eq(EstadoCita.CONFIRMADA), any())).thenReturn(List.of(vieja));

        assertThat(servicio.procesarVencidos().citasCerradas()).isEqualTo(1);
        verify(citaService).cambiarEstado(vieja, null, EstadoCita.COMPLETADA, null);
        verify(reputacion).quitarStrike(paciente.getId());
    }

    @Test
    @DisplayName("Distancia: el mismo punto da 0 m; sin ubicación del paciente no se calcula")
    void distancia() {
        assertThat(AsistenciaService.distanciaMetros(perfil, LAT, LON)).isZero();
        assertThat(AsistenciaService.distanciaMetros(perfil, null, LON)).isNull();
    }
}

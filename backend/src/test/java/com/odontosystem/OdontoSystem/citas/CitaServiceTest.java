package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.CitaDtos.ReservaRequest;
import com.odontosystem.OdontoSystem.citas.CitaDtos.Turno;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoReputacion;
import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.ReputacionPaciente;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.ReputacionPacienteRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reloj fijo: lunes 19/10/2026, 08:00 en Lima. */
@ExtendWith(MockitoExtension.class)
class CitaServiceTest {

    static final ZoneOffset LIMA = ZoneOffset.ofHours(-5);
    static final LocalDate LUNES = LocalDate.of(2026, 10, 19);

    @Mock private CitaRepository citaRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private ReputacionPacienteRepository reputacionRepository;
    @Mock private DisponibilidadService disponibilidadService;
    @Mock private BloqueoReservas bloqueoReservas;
    @Mock private ParametrosSistema parametros;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private EntityManager entityManager;

    private CitaService servicio;
    private Usuario paciente;
    private PerfilOdontologo perfil;
    private Servicio limpieza;

    @BeforeEach
    void setUp() {
        Clock reloj = Clock.fixed(Instant.parse("2026-10-19T13:00:00Z"), ZoneOffset.UTC);
        servicio = new CitaService(citaRepository, usuarioRepository, reputacionRepository, disponibilidadService,
                bloqueoReservas, parametros, entityManager, reloj);
        paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Paciente").rol(RolUsuario.PACIENTE).build();
        UUID odontologoId = UUID.randomUUID();
        perfil = PerfilOdontologo.builder().usuarioId(odontologoId)
                .usuario(Usuario.builder().id(odontologoId).nombreCompleto("Ana").rol(RolUsuario.ODONTOLOGO).build())
                .estadoVerificacion(EstadoVerificacionOdontologo.VERIFICADO).build();
        limpieza = Servicio.builder().id(UUID.randomUUID()).odontologo(perfil).titulo("Limpieza")
                .precioTotal(new BigDecimal("80.00")).montoDeposito(new BigDecimal("16.00")).duracionMinutos((short) 60).build();
        lenient().when(parametros.entero(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
    }

    private static OffsetDateTime lunesA(int hora) {
        return OffsetDateTime.of(LUNES, LocalTime.of(hora, 0), LIMA);
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    private void prepararReserva(OffsetDateTime turnoLibre) {
        when(usuarioRepository.findById(paciente.getId())).thenReturn(Optional.of(paciente));
        when(reputacionRepository.findById(paciente.getId())).thenReturn(Optional.empty());
        when(disponibilidadService.exigirPerfilPublicable(perfil.getUsuarioId())).thenReturn(perfil);
        when(disponibilidadService.exigirServicioActivo(perfil.getUsuarioId(), limpieza.getId())).thenReturn(limpieza);
        when(disponibilidadService.turnosLibres(perfil, limpieza, LUNES))
                .thenReturn(List.of(new Turno(turnoLibre, turnoLibre.plusHours(1))));
    }

    @Test
    @DisplayName("Reserva: queda PENDIENTE_PAGO, con fin = inicio + duración, y suelta el candado")
    void reservaOk() {
        prepararReserva(lunesA(10));
        when(bloqueoReservas.tomar(anyString(), eq(paciente.getId()))).thenReturn(true);
        when(citaRepository.saveAndFlush(any(Cita.class))).thenAnswer(inv -> {
            Cita c = inv.getArgument(0);
            c.setCreadoEn(OffsetDateTime.now());
            return c;
        });

        var r = servicio.reservar(paciente.getId(), new ReservaRequest(perfil.getUsuarioId(), limpieza.getId(), lunesA(10)));

        assertThat(r.estado()).isEqualTo(EstadoCita.PENDIENTE_PAGO);
        assertThat(r.fin()).isEqualTo(lunesA(11));
        assertThat(r.pagarAntesDe()).isNotNull();
        verify(bloqueoReservas).soltar(anyString());
    }

    @Test
    @DisplayName("Turno que no está en la lista de libres → 409 sin tocar la BD")
    void turnoOcupado() {
        prepararReserva(lunesA(9));
        assertThatThrownBy(() -> servicio.reservar(paciente.getId(),
                new ReservaRequest(perfil.getUsuarioId(), limpieza.getId(), lunesA(10))))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
        verify(citaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Otro paciente tiene el candado → 409; la BD rechaza el cruce → 409")
    void carreras() {
        prepararReserva(lunesA(10));
        when(bloqueoReservas.tomar(anyString(), eq(paciente.getId()))).thenReturn(false);
        assertThatThrownBy(() -> servicio.reservar(paciente.getId(),
                new ReservaRequest(perfil.getUsuarioId(), limpieza.getId(), lunesA(10))))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));

        when(bloqueoReservas.tomar(anyString(), eq(paciente.getId()))).thenReturn(true);
        when(citaRepository.saveAndFlush(any(Cita.class))).thenThrow(new DataIntegrityViolationException("ex_citas_sin_solape"));
        assertThatThrownBy(() -> servicio.reservar(paciente.getId(),
                new ReservaRequest(perfil.getUsuarioId(), limpieza.getId(), lunesA(10))))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
        verify(bloqueoReservas).soltar(anyString());
    }

    @Test
    @DisplayName("Paciente bloqueado por strikes → 403")
    void pacienteBloqueado() {
        when(usuarioRepository.findById(paciente.getId())).thenReturn(Optional.of(paciente));
        when(reputacionRepository.findById(paciente.getId())).thenReturn(Optional.of(
                ReputacionPaciente.builder().usuario(paciente).estado(EstadoReputacion.BLOQUEADO).build()));
        assertThatThrownBy(() -> servicio.reservar(paciente.getId(),
                new ReservaRequest(perfil.getUsuarioId(), limpieza.getId(), lunesA(10))))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(403));
    }

    private Cita cita(EstadoCita estado, OffsetDateTime inicio) {
        return Cita.builder().id(UUID.randomUUID()).paciente(paciente).odontologo(perfil).servicio(limpieza)
                .estado(estado).inicioProgramado(inicio).finProgramado(inicio.plusHours(1)).build();
    }

    private void guardarDevuelve() {
        when(citaRepository.saveAndFlush(any(Cita.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Cancelar: paciente con 12 h o más → con reembolso; con menos → sin reembolso; odontólogo → con reembolso")
    void reglasDeReembolso() {
        guardarDevuelve();
        Cita manana = cita(EstadoCita.CONFIRMADA, lunesA(8).plusDays(1));
        when(citaRepository.findById(manana.getId())).thenReturn(Optional.of(manana));
        assertThat(servicio.cancelar(paciente.getId(), manana.getId(), null).estado())
                .isEqualTo(EstadoCita.CANCELADA_CON_REEMBOLSO);

        Cita hoy = cita(EstadoCita.CONFIRMADA, lunesA(14));
        when(citaRepository.findById(hoy.getId())).thenReturn(Optional.of(hoy));
        assertThat(servicio.cancelar(paciente.getId(), hoy.getId(), "Me surgió algo").estado())
                .isEqualTo(EstadoCita.CANCELADA_SIN_REEMBOLSO);

        Cita hoy2 = cita(EstadoCita.CONFIRMADA, lunesA(14));
        when(citaRepository.findById(hoy2.getId())).thenReturn(Optional.of(hoy2));
        assertThat(servicio.cancelar(perfil.getUsuarioId(), hoy2.getId(), null).estado())
                .isEqualTo(EstadoCita.CANCELADA_CON_REEMBOLSO);
    }

    @Test
    @DisplayName("Cancelar sin haber pagado → sin reembolso; una cita ajena → 404")
    void cancelarSinPagoYAjena() {
        guardarDevuelve();
        Cita pendiente = cita(EstadoCita.PENDIENTE_PAGO, lunesA(8).plusDays(3));
        when(citaRepository.findById(pendiente.getId())).thenReturn(Optional.of(pendiente));
        assertThat(servicio.cancelar(paciente.getId(), pendiente.getId(), null).estado())
                .isEqualTo(EstadoCita.CANCELADA_SIN_REEMBOLSO);

        Cita otra = cita(EstadoCita.CONFIRMADA, lunesA(8).plusDays(3));
        when(citaRepository.findById(otra.getId())).thenReturn(Optional.of(otra));
        assertThatThrownBy(() -> servicio.cancelar(UUID.randomUUID(), otra.getId(), null))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
    }

    @Test
    @DisplayName("Inasistencia: antes de la tolerancia → 409; el tercer strike bloquea al paciente")
    void inasistencia() {
        Cita temprano = cita(EstadoCita.CONFIRMADA, lunesA(8).minusMinutes(5));
        when(citaRepository.findById(temprano.getId())).thenReturn(Optional.of(temprano));
        assertThatThrownBy(() -> servicio.marcarInasistencia(perfil.getUsuarioId(), temprano.getId()))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));

        guardarDevuelve();
        Cita tarde = cita(EstadoCita.CONFIRMADA, lunesA(7));
        when(citaRepository.findById(tarde.getId())).thenReturn(Optional.of(tarde));
        ReputacionPaciente reputacion = ReputacionPaciente.builder().usuario(paciente)
                .cantidadStrikes((short) 2).estado(EstadoReputacion.REGULAR).build();
        when(reputacionRepository.findById(paciente.getId())).thenReturn(Optional.of(reputacion));

        assertThat(servicio.marcarInasistencia(perfil.getUsuarioId(), tarde.getId()).estado())
                .isEqualTo(EstadoCita.INASISTENCIA_PENALIZADA);
        ArgumentCaptor<ReputacionPaciente> guardada = ArgumentCaptor.forClass(ReputacionPaciente.class);
        verify(reputacionRepository).save(guardada.capture());
        assertThat(guardada.getValue().getCantidadStrikes()).isEqualTo((short) 3);
        assertThat(guardada.getValue().getEstado()).isEqualTo(EstadoReputacion.BLOQUEADO);
    }

    @Test
    @DisplayName("Tarea: las reservas sin pago de hace más de 15 min se cancelan")
    void vencerSinPago() {
        Cita vieja = cita(EstadoCita.PENDIENTE_PAGO, lunesA(10));
        when(citaRepository.findByEstadoAndCreadoEnBefore(eq(EstadoCita.PENDIENTE_PAGO), any())).thenReturn(List.of(vieja));
        assertThat(servicio.vencerReservasSinPago()).isEqualTo(1);
        assertThat(vieja.getEstado()).isEqualTo(EstadoCita.CANCELADA_SIN_REEMBOLSO);
        assertThat(vieja.getMotivoCancelacion()).contains("a tiempo");
    }
}

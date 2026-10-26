package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.CitaDtos.Turno;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.PagoRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RF05 reprogramación. Reloj fijo: lunes 19/10/2026, 08:00 en Lima. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReprogramacionServiceTest {

    static final ZoneOffset LIMA = ZoneOffset.ofHours(-5);
    static final LocalDate MIERCOLES = LocalDate.of(2026, 10, 21);
    static final LocalDate JUEVES = LocalDate.of(2026, 10, 22);

    @Mock private CitaRepository citaRepository;
    @Mock private PagoRepository pagoRepository;
    @Mock private DisponibilidadService disponibilidadService;
    @Mock private BloqueoReservas bloqueoReservas;
    @Mock private ParametrosSistema parametros;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private EntityManager entityManager;
    @Mock private ApplicationEventPublisher eventos;

    private ReprogramacionService servicio;
    private Usuario paciente;
    private PerfilOdontologo perfil;
    private Servicio limpieza;

    @BeforeEach
    void setUp() {
        Clock reloj = Clock.fixed(Instant.parse("2026-10-19T13:00:00Z"), ZoneOffset.UTC);
        servicio = new ReprogramacionService(citaRepository, pagoRepository, disponibilidadService, bloqueoReservas,
                parametros, entityManager, reloj, eventos, mock(CitaService.class));
        paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Luis Paredes").rol(RolUsuario.PACIENTE).build();
        UUID odontologoId = UUID.randomUUID();
        perfil = PerfilOdontologo.builder().usuarioId(odontologoId).zonaHoraria("America/Lima")
                .usuario(Usuario.builder().id(odontologoId).nombreCompleto("Ana").rol(RolUsuario.ODONTOLOGO).build())
                .build();
        limpieza = Servicio.builder().id(UUID.randomUUID()).odontologo(perfil).titulo("Limpieza")
                .precioTotal(new BigDecimal("80.00")).montoDeposito(new BigDecimal("16.00"))
                .duracionMinutos((short) 60).build();
        when(parametros.entero(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(disponibilidadService.exigirServicioActivo(perfil.getUsuarioId(), limpieza.getId())).thenReturn(limpieza);
        when(bloqueoReservas.tomar(anyString(), eq(paciente.getId()))).thenReturn(true);
        when(citaRepository.saveAndFlush(any(Cita.class))).thenAnswer(inv -> {
            Cita c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
    }

    private static OffsetDateTime a(LocalDate dia, int hora) {
        return OffsetDateTime.of(dia, LocalTime.of(hora, 0), LIMA);
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    private Cita cita(EstadoCita estado, OffsetDateTime inicio, UUID origen) {
        Cita c = Cita.builder().id(UUID.randomUUID()).paciente(paciente).odontologo(perfil).servicio(limpieza)
                .inicioProgramado(inicio).finProgramado(inicio.plusHours(1)).estado(estado).citaOrigenId(origen)
                .zonaHoraria("America/Lima").build();
        when(citaRepository.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private void libre(OffsetDateTime inicio) {
        when(disponibilidadService.turnosLibres(perfil, limpieza, inicio.toLocalDate()))
                .thenReturn(List.of(new Turno(inicio, inicio.plusHours(1))));
    }

    private Cita nuevaGuardada() {
        ArgumentCaptor<Cita> guardadas = ArgumentCaptor.forClass(Cita.class);
        verify(citaRepository, times(2)).saveAndFlush(guardadas.capture());
        return guardadas.getAllValues().get(1);
    }

    @Test
    @DisplayName("Cita confirmada con 2 días de anticipación: la original queda REPROGRAMADA y la nueva CONFIRMADA con el pago")
    void reprogramaOk() {
        Cita original = cita(EstadoCita.CONFIRMADA, a(MIERCOLES, 10), null);
        libre(a(JUEVES, 15));

        servicio.reprogramar(paciente.getId(), original.getId(), a(JUEVES, 15));

        assertThat(original.getEstado()).isEqualTo(EstadoCita.REPROGRAMADA);
        Cita nueva = nuevaGuardada();
        assertThat(nueva.getEstado()).isEqualTo(EstadoCita.CONFIRMADA);
        assertThat(nueva.getCitaOrigenId()).isEqualTo(original.getId());
        assertThat(nueva.getInicioProgramado()).isEqualTo(a(JUEVES, 15));
        assertThat(nueva.getFinProgramado()).isEqualTo(a(JUEVES, 16));
        assertThat(nueva.getPaciente()).isSameAs(paciente);
        verify(pagoRepository).moverACita(original.getId(), nueva.getId());
        ArgumentCaptor<Object> eventosPublicados = ArgumentCaptor.forClass(Object.class);
        verify(eventos, times(2)).publishEvent(eventosPublicados.capture());
        assertThat(eventosPublicados.getAllValues()).containsExactly(
                new CitaCambioEstado(original.getId(), EstadoCita.CONFIRMADA, EstadoCita.REPROGRAMADA),
                new CitaReprogramada(original.getId(), nueva.getId(), a(MIERCOLES, 10)));
        verify(bloqueoReservas).soltar(anyString());
    }

    @Test
    @DisplayName("Si esperaba la confirmación del odontólogo, la nueva también queda PENDIENTE_CONFIRMACION")
    void conservaPendienteDeConfirmacion() {
        Cita original = cita(EstadoCita.PENDIENTE_CONFIRMACION, a(MIERCOLES, 10), null);
        libre(a(JUEVES, 9));

        servicio.reprogramar(paciente.getId(), original.getId(), a(JUEVES, 9));

        assertThat(nuevaGuardada().getEstado()).isEqualTo(EstadoCita.PENDIENTE_CONFIRMACION);
    }

    @Test
    @DisplayName("Con menos de 24 h de anticipación → 409 y no se toca nada")
    void menosDe24Horas() {
        Cita manana = cita(EstadoCita.CONFIRMADA, a(LocalDate.of(2026, 10, 20), 7), null); // en 23 h

        assertThatThrownBy(() -> servicio.reprogramar(paciente.getId(), manana.getId(), a(JUEVES, 10)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409))
                .hasMessageContaining("24 horas");
        assertThat(manana.getEstado()).isEqualTo(EstadoCita.CONFIRMADA);
        verify(citaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Una cita que ya nació de una reprogramación no se vuelve a reprogramar (máximo 1)")
    void soloUnaVez() {
        Cita primera = cita(EstadoCita.REPROGRAMADA, a(MIERCOLES, 9), null);
        Cita segunda = cita(EstadoCita.CONFIRMADA, a(MIERCOLES, 11), primera.getId());

        assertThatThrownBy(() -> servicio.reprogramar(paciente.getId(), segunda.getId(), a(JUEVES, 10)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409))
                .hasMessageContaining("una vez");
        verify(citaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Con MAX_REPROGRAMACIONES_POR_CITA = 2 sí se permite una segunda")
    void maximoDesdeConfiguracion() {
        when(parametros.entero(eq(ReprogramacionService.MAXIMO), anyInt())).thenReturn(2);
        Cita primera = cita(EstadoCita.REPROGRAMADA, a(MIERCOLES, 9), null);
        Cita segunda = cita(EstadoCita.CONFIRMADA, a(MIERCOLES, 11), primera.getId());
        libre(a(JUEVES, 10));

        servicio.reprogramar(paciente.getId(), segunda.getId(), a(JUEVES, 10));

        assertThat(nuevaGuardada().getCitaOrigenId()).isEqualTo(segunda.getId());
    }

    @Test
    @DisplayName("Una reserva sin pagar no se reprograma: se cancela y se vuelve a reservar")
    void sinPagar() {
        Cita pendiente = cita(EstadoCita.PENDIENTE_PAGO, a(MIERCOLES, 10), null);

        assertThatThrownBy(() -> servicio.reprogramar(paciente.getId(), pendiente.getId(), a(JUEVES, 10)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409))
                .hasMessageContaining("no está pagada");
    }

    @Test
    @DisplayName("Completadas o canceladas → 409")
    void estadoFinal() {
        Cita completada = cita(EstadoCita.COMPLETADA, a(MIERCOLES, 10), null);

        assertThatThrownBy(() -> servicio.reprogramar(paciente.getId(), completada.getId(), a(JUEVES, 10)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
    }

    @Test
    @DisplayName("La cita de otro paciente → 404")
    void otroPaciente() {
        Cita ajena = cita(EstadoCita.CONFIRMADA, a(MIERCOLES, 10), null);

        assertThatThrownBy(() -> servicio.reprogramar(UUID.randomUUID(), ajena.getId(), a(JUEVES, 10)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
    }

    @Test
    @DisplayName("Horario nuevo ocupado → 409, no se mueve el pago y se suelta el candado")
    void horarioOcupado() {
        Cita original = cita(EstadoCita.CONFIRMADA, a(MIERCOLES, 10), null);
        libre(a(JUEVES, 9)); // solo las 9 están libres

        assertThatThrownBy(() -> servicio.reprogramar(paciente.getId(), original.getId(), a(JUEVES, 15)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
        verify(pagoRepository, never()).moverACita(any(), any());
        verify(eventos, never()).publishEvent(any(CitaReprogramada.class));
        verify(bloqueoReservas).soltar(anyString());
    }

    @Test
    @DisplayName("Otro paciente ganó el turno justo al guardar (ex_citas_sin_solape) → 409")
    void carreraAlGuardar() {
        Cita original = cita(EstadoCita.CONFIRMADA, a(MIERCOLES, 10), null);
        libre(a(JUEVES, 15));
        when(citaRepository.saveAndFlush(any(Cita.class)))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new DataIntegrityViolationException("ex_citas_sin_solape"));

        assertThatThrownBy(() -> servicio.reprogramar(paciente.getId(), original.getId(), a(JUEVES, 15)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409))
                .hasMessageContaining("acaba de ser reservado");
        verify(pagoRepository, never()).moverACita(any(), any());
    }

    @Test
    @DisplayName("Si otro paciente tiene tomado el candado de ese horario → 409 sin tocar la cita")
    void candadoOcupado() {
        Cita original = cita(EstadoCita.CONFIRMADA, a(MIERCOLES, 10), null);
        when(bloqueoReservas.tomar(anyString(), eq(paciente.getId()))).thenReturn(false);

        assertThatThrownBy(() -> servicio.reprogramar(paciente.getId(), original.getId(), a(JUEVES, 15)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
        assertThat(original.getEstado()).isEqualTo(EstadoCita.CONFIRMADA);
    }
}

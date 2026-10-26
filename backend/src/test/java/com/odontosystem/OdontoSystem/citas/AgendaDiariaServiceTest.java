package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.AgendaDiariaService.AgendaHoyResponse;
import com.odontosystem.OdontoSystem.citas.AgendaDiariaService.ConfirmacionRequest;
import com.odontosystem.OdontoSystem.entity.BloqueoAgenda;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.ConfirmacionAgenda;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.BloqueoAgendaRepository;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.ConfirmacionAgendaRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
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

import java.time.Clock;
import java.time.LocalDate;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reloj fijo: lunes 19/10/2026, 07:00 en Lima. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgendaDiariaServiceTest {

    static final ZoneOffset LIMA = ZoneOffset.ofHours(-5);
    static final OffsetDateTime AHORA = OffsetDateTime.of(2026, 10, 19, 7, 0, 0, 0, LIMA);
    static final LocalDate HOY = AHORA.toLocalDate();

    @Mock private ConfirmacionAgendaRepository confirmaciones;
    @Mock private CitaRepository citas;
    @Mock private PerfilOdontologoRepository perfiles;
    @Mock private BloqueoAgendaRepository bloqueos;
    @Mock private CitaService citaService;

    private AgendaDiariaService servicio;
    private PerfilOdontologo perfil;

    @BeforeEach
    void setUp() {
        servicio = new AgendaDiariaService(confirmaciones, citas, perfiles, bloqueos, citaService,
                Clock.fixed(AHORA.toInstant(), ZoneOffset.UTC));
        UUID id = UUID.randomUUID();
        perfil = PerfilOdontologo.builder().usuarioId(id).zonaHoraria("America/Lima")
                .usuario(Usuario.builder().id(id).nombreCompleto("Dra. Ana").correo("ana@correo.pe").build()).build();
        when(perfiles.findById(id)).thenReturn(Optional.of(perfil));
        when(confirmaciones.findByOdontologoIdAndFecha(id, HOY)).thenReturn(Optional.empty());
    }

    private Cita cita(int hora) {
        OffsetDateTime inicio = OffsetDateTime.of(HOY, java.time.LocalTime.of(hora, 0), LIMA);
        return Cita.builder().id(UUID.randomUUID()).odontologo(perfil).estado(EstadoCita.CONFIRMADA)
                .inicioProgramado(inicio).finProgramado(inicio.plusHours(1)).build();
    }

    @Test
    @DisplayName("Sin responder: atiende = null")
    void sinResponder() {
        when(citas.cruzadas(eq(perfil.getUsuarioId()), any(), any(), any())).thenReturn(List.of(cita(9), cita(11)));
        AgendaHoyResponse r = servicio.hoy(perfil.getUsuarioId());
        assertThat(r.atiende()).isNull();
        assertThat(r.citasHoy()).isEqualTo(2);
        assertThat(r.fecha()).isEqualTo(HOY);
    }

    @Test
    @DisplayName("\"Sí atiendo\": se guarda la confirmación y no se cancela nada")
    void atiende() {
        AgendaHoyResponse r = servicio.confirmar(perfil.getUsuarioId(), new ConfirmacionRequest(true, null));
        ArgumentCaptor<ConfirmacionAgenda> guardada = ArgumentCaptor.forClass(ConfirmacionAgenda.class);
        verify(confirmaciones).save(guardada.capture());
        assertThat(guardada.getValue().isAtiende()).isTrue();
        assertThat(guardada.getValue().getFecha()).isEqualTo(HOY);
        assertThat(r.citasCanceladas()).isZero();
        verify(citaService, never()).cancelar(any(), any(), any());
    }

    @Test
    @DisplayName("\"No puedo atender hoy\": cancela sus citas de hoy (con reembolso) y bloquea el resto del día")
    void noAtiende() {
        Cita nueve = cita(9);
        Cita once = cita(11);
        when(citas.cruzadas(eq(perfil.getUsuarioId()), any(), any(), any())).thenReturn(List.of(nueve, once));

        AgendaHoyResponse r = servicio.confirmar(perfil.getUsuarioId(), new ConfirmacionRequest(false, "Estoy enfermo"));

        assertThat(r.citasCanceladas()).isEqualTo(2);
        verify(citaService).cancelar(eq(perfil.getUsuarioId()), eq(nueve.getId()), anyString());
        verify(citaService).cancelar(eq(perfil.getUsuarioId()), eq(once.getId()), anyString());
        ArgumentCaptor<BloqueoAgenda> bloqueo = ArgumentCaptor.forClass(BloqueoAgenda.class);
        verify(bloqueos).save(bloqueo.capture());
        assertThat(bloqueo.getValue().getFin()).isEqualTo(HOY.plusDays(1).atStartOfDay(java.time.ZoneId.of("America/Lima")).toOffsetDateTime());
    }

    @Test
    @DisplayName("Si ya dijo que no atiende, no puede volver a confirmar (409)")
    void yaDijoQueNo() {
        when(confirmaciones.findByOdontologoIdAndFecha(perfil.getUsuarioId(), HOY)).thenReturn(Optional.of(
                ConfirmacionAgenda.builder().odontologoId(perfil.getUsuarioId()).fecha(HOY).atiende(false).build()));
        assertThatThrownBy(() -> servicio.confirmar(perfil.getUsuarioId(), new ConfirmacionRequest(true, null)))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    }

    @Test
    @DisplayName("Aviso de la mañana: solo a odontólogos con citas hoy que no confirmaron")
    void avisoDeLaManana() {
        when(citas.empiezanEntre(any(), any(), any())).thenReturn(List.of(cita(9), cita(11)));
        var avisos = servicio.pendientesDeConfirmar();
        assertThat(avisos).hasSize(1);
        assertThat(avisos.get(0).citas()).isEqualTo(2);
        assertThat(avisos.get(0).correo()).isEqualTo("ana@correo.pe");

        when(confirmaciones.findByOdontologoIdAndFecha(perfil.getUsuarioId(), HOY)).thenReturn(Optional.of(
                ConfirmacionAgenda.builder().atiende(true).build()));
        assertThat(servicio.pendientesDeConfirmar()).isEmpty();
    }
}

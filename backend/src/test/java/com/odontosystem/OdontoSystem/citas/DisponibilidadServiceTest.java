package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.CitaDtos.Turno;
import com.odontosystem.OdontoSystem.entity.BloqueoAgenda;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.DiaSemana;
import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.HorarioAtencion;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.BloqueoAgendaRepository;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.HorarioAtencionRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ServicioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Turnos libres con un reloj fijo: lunes 19/10/2026, 08:00 en Lima (13:00 UTC). */
@ExtendWith(MockitoExtension.class)
class DisponibilidadServiceTest {

    static final ZoneOffset LIMA = ZoneOffset.ofHours(-5);
    static final LocalDate LUNES = LocalDate.of(2026, 10, 19);

    @Mock private PerfilOdontologoRepository perfilRepository;
    @Mock private ServicioRepository servicioRepository;
    @Mock private HorarioAtencionRepository horarioRepository;
    @Mock private CitaRepository citaRepository;
    @Mock private BloqueoAgendaRepository bloqueoRepository;

    private DisponibilidadService servicio;
    private final UUID odontologoId = UUID.randomUUID();
    private PerfilOdontologo perfil;
    private Servicio limpieza;

    @BeforeEach
    void setUp() {
        Clock reloj = Clock.fixed(Instant.parse("2026-10-19T13:00:00Z"), ZoneOffset.UTC);
        servicio = new DisponibilidadService(perfilRepository, servicioRepository, horarioRepository,
                citaRepository, bloqueoRepository, reloj);
        perfil = PerfilOdontologo.builder().usuarioId(odontologoId)
                .usuario(Usuario.builder().id(odontologoId).nombreCompleto("Ana").build())
                .estadoVerificacion(EstadoVerificacionOdontologo.VERIFICADO).build();
        limpieza = Servicio.builder().id(UUID.randomUUID()).odontologo(perfil).titulo("Limpieza")
                .precioTotal(new BigDecimal("80.00")).duracionMinutos((short) 60).build();

        // Lunes 09:00-12:00 cada 30 min; martes 15:00-18:00
        lenient().when(horarioRepository.findByOdontologo_UsuarioIdOrderByDiaSemanaAscHoraInicioAsc(odontologoId)).thenReturn(List.of(
                horario(DiaSemana.LUNES, 9, 12), horario(DiaSemana.MARTES, 15, 18)));
        lenient().when(citaRepository.cruzadas(eq(odontologoId), anyList(), any(), any())).thenReturn(List.of());
        lenient().when(bloqueoRepository.findByOdontologo_UsuarioIdAndInicioBeforeAndFinAfter(eq(odontologoId), any(), any()))
                .thenReturn(List.of());
    }

    private HorarioAtencion horario(DiaSemana dia, int desde, int hasta) {
        return HorarioAtencion.builder().odontologo(perfil).diaSemana(dia)
                .horaInicio(LocalTime.of(desde, 0)).horaFin(LocalTime.of(hasta, 0)).intervaloMinutos((short) 30).build();
    }

    private static OffsetDateTime lunesA(int hora, int minuto) {
        return OffsetDateTime.of(LUNES, LocalTime.of(hora, minuto), LIMA);
    }

    private static List<String> horas(List<Turno> turnos) {
        return turnos.stream().map(t -> t.inicio().atZoneSameInstant(LIMA).toLocalTime().toString()).toList();
    }

    @Test
    @DisplayName("Lunes 9-12 cada 30 min con servicio de 60 min: el último turno empieza 11:00")
    void generaTurnos() {
        assertThat(horas(servicio.turnosLibres(perfil, limpieza, LUNES)))
                .containsExactly("09:00", "09:30", "10:00", "10:30", "11:00");
    }

    @Test
    @DisplayName("Quita los turnos que se cruzan con una cita activa o un bloqueo")
    void quitaOcupados() {
        Cita cita = Cita.builder().inicioProgramado(lunesA(9, 30)).finProgramado(lunesA(10, 30)).build();
        when(citaRepository.cruzadas(eq(odontologoId), anyList(), any(), any())).thenReturn(List.of(cita));
        BloqueoAgenda bloqueo = BloqueoAgenda.builder().inicio(lunesA(11, 0)).fin(lunesA(12, 0)).build();
        when(bloqueoRepository.findByOdontologo_UsuarioIdAndInicioBeforeAndFinAfter(eq(odontologoId), any(), any()))
                .thenReturn(List.of(bloqueo));

        // 09:00 (termina 10:00, cruza 9:30), 09:30, 10:00 cruzan la cita; 10:30 y 11:00 cruzan el bloqueo
        assertThat(horas(servicio.turnosLibres(perfil, limpieza, LUNES))).isEmpty();
    }

    @Test
    @DisplayName("Un día sin horario no tiene turnos; los turnos pasados no se ofrecen")
    void sinHorarioYPasados() {
        assertThat(servicio.turnosLibres(perfil, limpieza, LocalDate.of(2026, 10, 21))).isEmpty(); // miércoles
        Clock tarde = Clock.fixed(Instant.parse("2026-10-19T15:15:00Z"), ZoneOffset.UTC); // 10:15 en Lima
        var conReloj = new DisponibilidadService(perfilRepository, servicioRepository, horarioRepository,
                citaRepository, bloqueoRepository, tarde);
        assertThat(horas(conReloj.turnosLibres(perfil, limpieza, LUNES))).containsExactly("10:30", "11:00");
    }

    @Test
    @DisplayName("Fecha pasada o a más de 60 días → 400; odontólogo no verificado → 404")
    void validaFechaYPerfil() {
        when(perfilRepository.findById(odontologoId)).thenReturn(Optional.of(perfil));
        when(servicioRepository.findByIdAndOdontologo_UsuarioId(limpieza.getId(), odontologoId)).thenReturn(Optional.of(limpieza));
        assertThatThrownBy(() -> servicio.disponibilidad(odontologoId, limpieza.getId(), LUNES.minusDays(1)))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> servicio.disponibilidad(odontologoId, limpieza.getId(), LUNES.plusDays(61)))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));

        perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.PENDIENTE);
        assertThatThrownBy(() -> servicio.disponibilidad(odontologoId, limpieza.getId(), LUNES))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }
}

package com.odontosystem.OdontoSystem.odontologos;

import com.odontosystem.OdontoSystem.entity.CategoriaServicio;
import com.odontosystem.OdontoSystem.entity.DiaSemana;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.BloqueoRequest;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.HorarioDto;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ServicioRequest;
import com.odontosystem.OdontoSystem.repository.BloqueoAgendaRepository;
import com.odontosystem.OdontoSystem.repository.CategoriaServicioRepository;
import com.odontosystem.OdontoSystem.repository.HorarioAtencionRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ServicioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MiConsultorioServiceTest {

    @Mock private PerfilOdontologoRepository perfilRepository;
    @Mock private ServicioRepository servicioRepository;
    @Mock private CategoriaServicioRepository categoriaRepository;
    @Mock private HorarioAtencionRepository horarioRepository;
    @Mock private BloqueoAgendaRepository bloqueoRepository;
    @Mock private EntityManager entityManager;

    private MiConsultorioService servicio;
    private final UUID odontologoId = UUID.randomUUID();
    private final PerfilOdontologo perfil = PerfilOdontologo.builder().usuarioId(odontologoId).build();

    @BeforeEach
    void setUp() {
        servicio = new MiConsultorioService(perfilRepository, servicioRepository, categoriaRepository,
                horarioRepository, bloqueoRepository, entityManager);
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    @Test
    @DisplayName("Horario: dos tramos del mismo día que se cruzan → 400")
    void horarioCruzado() {
        List<HorarioDto> horarios = List.of(
                new HorarioDto(DiaSemana.LUNES, LocalTime.of(9, 0), LocalTime.of(13, 0), (short) 30),
                new HorarioDto(DiaSemana.LUNES, LocalTime.of(12, 0), LocalTime.of(18, 0), (short) 30));
        assertThatThrownBy(() -> MiConsultorioService.validarHorarios(horarios)).satisfies(e -> assertThat(estado(e)).isEqualTo(400));
    }

    @Test
    @DisplayName("Horario: mañana y tarde el mismo día, y fin antes de inicio")
    void horarioPartidoYFinInvalido() {
        assertThatCode(() -> MiConsultorioService.validarHorarios(List.of(
                new HorarioDto(DiaSemana.LUNES, LocalTime.of(9, 0), LocalTime.of(13, 0), (short) 30),
                new HorarioDto(DiaSemana.LUNES, LocalTime.of(15, 0), LocalTime.of(19, 0), (short) 30),
                new HorarioDto(DiaSemana.MARTES, LocalTime.of(9, 0), LocalTime.of(13, 0), null)))).doesNotThrowAnyException();
        assertThatThrownBy(() -> MiConsultorioService.validarHorarios(List.of(
                new HorarioDto(DiaSemana.JUEVES, LocalTime.of(18, 0), LocalTime.of(9, 0), (short) 30))))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
    }

    @Test
    @DisplayName("Crear servicio: recarga la entidad para devolver el depósito que calculó la BD")
    void creaServicio() {
        CategoriaServicio limpieza = new CategoriaServicio((short) 2, "Limpieza y prevención", true);
        when(perfilRepository.findById(odontologoId)).thenReturn(Optional.of(perfil));
        when(categoriaRepository.findById((short) 2)).thenReturn(Optional.of(limpieza));
        when(servicioRepository.saveAndFlush(any(Servicio.class))).thenAnswer(inv -> inv.getArgument(0));

        var respuesta = servicio.crearServicio(odontologoId,
                new ServicioRequest((short) 2, "  Limpieza dental ", null, new BigDecimal("80.00"), (short) 45));

        assertThat(respuesta.titulo()).isEqualTo("Limpieza dental");
        assertThat(respuesta.categoria()).isEqualTo("Limpieza y prevención");
        verify(entityManager).refresh(any(Servicio.class));
    }

    @Test
    @DisplayName("Categoría inexistente → 400; servicio de otro odontólogo → 404")
    void validaCategoriaYDueno() {
        when(perfilRepository.findById(odontologoId)).thenReturn(Optional.of(perfil));
        when(categoriaRepository.findById((short) 99)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.crearServicio(odontologoId,
                new ServicioRequest((short) 99, "X", null, BigDecimal.TEN, (short) 30)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));

        UUID ajeno = UUID.randomUUID();
        when(servicioRepository.findByIdAndOdontologo_UsuarioId(ajeno, odontologoId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.desactivarServicio(odontologoId, ajeno))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
    }

    @Test
    @DisplayName("Bloqueo: fin antes de inicio o de más de 60 días → 400")
    void validaBloqueo() {
        when(perfilRepository.findById(odontologoId)).thenReturn(Optional.of(perfil));
        OffsetDateTime manana = OffsetDateTime.now().plusDays(1);
        assertThatThrownBy(() -> servicio.crearBloqueo(odontologoId, new BloqueoRequest(manana, manana.minusHours(2), null)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
        assertThatThrownBy(() -> servicio.crearBloqueo(odontologoId, new BloqueoRequest(manana, manana.plusDays(90), null)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
        verify(bloqueoRepository, never()).save(any());
    }
}

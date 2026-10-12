package com.odontosystem.OdontoSystem.resenas;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Resena;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ResenaRepository;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenaRequest;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenaResponse;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenasOdontologoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResenaServiceTest {

    @Mock private ResenaRepository resenas;
    @Mock private CitaRepository citas;
    @Mock private PerfilOdontologoRepository perfiles;

    private ResenaService servicio;
    private Usuario paciente;
    private PerfilOdontologo odontologo;

    @BeforeEach
    void setUp() {
        servicio = new ResenaService(resenas, citas, perfiles);
        paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Lucía Ramos Peña").build();
        Usuario usuarioOdontologo = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Dra. Ana Torres").build();
        odontologo = PerfilOdontologo.builder().usuarioId(usuarioOdontologo.getId()).usuario(usuarioOdontologo)
                .calificacionPromedio(new BigDecimal("4.5")).totalResenas(2).build();
        when(resenas.saveAndFlush(any(Resena.class))).thenAnswer(i -> {
            Resena r = i.getArgument(0);
            if (r.getId() == null) r.setId(1L);
            if (r.getCreadoEn() == null) r.setCreadoEn(OffsetDateTime.now());
            return r;
        });
    }

    private Cita cita(EstadoCita estado) {
        Cita c = Cita.builder().id(UUID.randomUUID()).paciente(paciente).odontologo(odontologo).estado(estado).build();
        when(citas.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    @Test
    @DisplayName("Califica una cita atendida: guarda paciente, odontólogo, estrellas y comentario limpio")
    void calificaCitaCompletada() {
        Cita c = cita(EstadoCita.COMPLETADA);

        ResenaResponse r = servicio.crear(paciente.getId(), c.getId(), new ResenaRequest((short) 5, "  Muy buena atención  "));

        ArgumentCaptor<Resena> captor = ArgumentCaptor.forClass(Resena.class);
        verify(resenas).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOdontologoId()).isEqualTo(odontologo.getUsuarioId());
        assertThat(captor.getValue().getPaciente()).isEqualTo(paciente);
        assertThat(r.calificacion()).isEqualTo((short) 5);
        assertThat(r.comentario()).isEqualTo("Muy buena atención");
    }

    @Test
    @DisplayName("Un comentario vacío se guarda como sin comentario")
    void comentarioVacio() {
        Cita c = cita(EstadoCita.COMPLETADA);

        assertThat(servicio.crear(paciente.getId(), c.getId(), new ResenaRequest((short) 4, "   ")).comentario()).isNull();
    }

    @Test
    @DisplayName("No se puede calificar una cita que todavía no fue atendida (409)")
    void citaNoAtendida() {
        Cita c = cita(EstadoCita.CONFIRMADA);

        assertThatThrownBy(() -> servicio.crear(paciente.getId(), c.getId(), new ResenaRequest((short) 5, null)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
        verify(resenas, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Una sola reseña por cita (409)")
    void unaPorCita() {
        Cita c = cita(EstadoCita.COMPLETADA);
        when(resenas.existsByCita_Id(c.getId())).thenReturn(true);

        assertThatThrownBy(() -> servicio.crear(paciente.getId(), c.getId(), new ResenaRequest((short) 3, null)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
    }

    @Test
    @DisplayName("Nadie puede calificar la cita de otro paciente (404)")
    void citaAjena() {
        Cita c = cita(EstadoCita.COMPLETADA);

        assertThatThrownBy(() -> servicio.crear(UUID.randomUUID(), c.getId(), new ResenaRequest((short) 1, "mal")))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
    }

    @Test
    @DisplayName("Calificación fuera de 1 a 5 → 400 (además de la validación del controlador)")
    void calificacionFueraDeRango() {
        Cita c = cita(EstadoCita.COMPLETADA);

        assertThatThrownBy(() -> servicio.crear(paciente.getId(), c.getId(), new ResenaRequest((short) 6, null)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
    }

    @Test
    @DisplayName("El paciente puede corregir su reseña")
    void editarResena() {
        Cita c = cita(EstadoCita.COMPLETADA);
        Resena existente = Resena.builder().id(7L).cita(c).odontologoId(odontologo.getUsuarioId()).paciente(paciente)
                .calificacion((short) 2).comentario("regular").creadoEn(OffsetDateTime.now()).build();
        when(resenas.findByCita_IdAndPaciente_Id(c.getId(), paciente.getId())).thenReturn(Optional.of(existente));

        ResenaResponse r = servicio.editar(paciente.getId(), c.getId(), new ResenaRequest((short) 4, "Mejoró en la segunda visita"));

        assertThat(r.calificacion()).isEqualTo((short) 4);
        assertThat(r.comentario()).isEqualTo("Mejoró en la segunda visita");
    }

    @Test
    @DisplayName("Reseñas públicas: promedio, distribución de 5 a 1 y nombre del paciente abreviado")
    void resenasPublicas() {
        when(perfiles.findById(odontologo.getUsuarioId())).thenReturn(Optional.of(odontologo));
        when(resenas.distribucion(odontologo.getUsuarioId()))
                .thenReturn(List.of(new Object[]{(short) 5, 1L}, new Object[]{(short) 4, 1L}));
        Resena r = Resena.builder().id(1L).odontologoId(odontologo.getUsuarioId()).paciente(paciente)
                .calificacion((short) 5).comentario("Excelente").creadoEn(OffsetDateTime.now()).build();
        when(resenas.delOdontologo(eq(odontologo.getUsuarioId()), any()))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 10), 2));

        ResenasOdontologoResponse resp = servicio.deOdontologo(odontologo.getUsuarioId(), 0, 10);

        assertThat(resp.calificacion()).isEqualByComparingTo("4.50");
        assertThat(resp.totalResenas()).isEqualTo(2);
        assertThat(resp.distribucion()).containsExactly(
                org.assertj.core.api.Assertions.entry(5, 1L), org.assertj.core.api.Assertions.entry(4, 1L),
                org.assertj.core.api.Assertions.entry(3, 0L), org.assertj.core.api.Assertions.entry(2, 0L),
                org.assertj.core.api.Assertions.entry(1, 0L));
        assertThat(resp.resenas()).singleElement().satisfies(p -> assertThat(p.paciente()).isEqualTo("Lucía R."));
    }

    @Test
    @DisplayName("Odontólogo inexistente → 404; tamaño de página inválido → 400")
    void erroresDeConsulta() {
        assertThatThrownBy(() -> servicio.deOdontologo(UUID.randomUUID(), 0, 10))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
        assertThatThrownBy(() -> servicio.deOdontologo(odontologo.getUsuarioId(), 0, 500))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
    }
}

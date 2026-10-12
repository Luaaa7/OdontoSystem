package com.odontosystem.OdontoSystem.resenas;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Resena;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ResenaRepository;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenaRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResenaServiceTest {

    @Mock private ResenaRepository resenaRepository;
    @Mock private CitaRepository citaRepository;
    @Mock private PerfilOdontologoRepository perfilRepository;

    private ResenaService servicio;
    private Usuario paciente;
    private PerfilOdontologo perfil;
    private Cita cita;

    @BeforeEach
    void setUp() {
        servicio = new ResenaService(resenaRepository, citaRepository, perfilRepository);
        paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Juan Carlos Pérez Quispe")
                .rol(RolUsuario.PACIENTE).build();
        UUID odontologoId = UUID.randomUUID();
        perfil = PerfilOdontologo.builder().usuarioId(odontologoId)
                .usuario(Usuario.builder().id(odontologoId).nombreCompleto("Ana").rol(RolUsuario.ODONTOLOGO).build())
                .estadoVerificacion(EstadoVerificacionOdontologo.VERIFICADO).build();
        cita = Cita.builder().id(UUID.randomUUID()).paciente(paciente).odontologo(perfil)
                .estado(EstadoCita.COMPLETADA).build();
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    private void prepararCitaSinResena() {
        when(citaRepository.findById(cita.getId())).thenReturn(Optional.of(cita));
        when(resenaRepository.existsByCita_Id(cita.getId())).thenReturn(false);
    }

    private void guardarDevuelveLoMismo() {
        when(resenaRepository.saveAndFlush(any(Resena.class))).thenAnswer(inv -> {
            Resena r = inv.getArgument(0);
            r.setId(1L);
            r.setCreadoEn(OffsetDateTime.now());
            return r;
        });
    }

    // ------------------------------------------------------------------
    // Crear
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Reseña OK: usa el paciente y el odontólogo de la cita, y limpia el comentario")
    void creaResena() {
        prepararCitaSinResena();
        guardarDevuelveLoMismo();

        var r = servicio.crear(paciente.getId(), cita.getId(), new ResenaRequest((short) 5, "  Excelente atención  "));

        assertThat(r.calificacion()).isEqualTo((short) 5);
        assertThat(r.comentario()).isEqualTo("Excelente atención");
        assertThat(r.paciente()).isEqualTo("Juan Q.");
        ArgumentCaptor<Resena> guardada = ArgumentCaptor.forClass(Resena.class);
        verify(resenaRepository).saveAndFlush(guardada.capture());
        assertThat(guardada.getValue().getCita()).isSameAs(cita);
        assertThat(guardada.getValue().getPaciente()).isSameAs(paciente);
        assertThat(guardada.getValue().getOdontologo()).isSameAs(perfil);
    }

    @Test
    @DisplayName("Comentario en blanco se guarda como null")
    void comentarioEnBlanco() {
        prepararCitaSinResena();
        guardarDevuelveLoMismo();

        var r = servicio.crear(paciente.getId(), cita.getId(), new ResenaRequest((short) 4, "   "));

        assertThat(r.comentario()).isNull();
    }

    @Test
    @DisplayName("Cita de otro paciente → 404 sin guardar nada")
    void citaAjena() {
        when(citaRepository.findById(cita.getId())).thenReturn(Optional.of(cita));

        assertThatThrownBy(() -> servicio.crear(UUID.randomUUID(), cita.getId(), new ResenaRequest((short) 5, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
        verify(resenaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Cita que no está COMPLETADA → 409 sin guardar nada")
    void citaNoAtendida() {
        cita.setEstado(EstadoCita.CONFIRMADA);
        when(citaRepository.findById(cita.getId())).thenReturn(Optional.of(cita));

        assertThatThrownBy(() -> servicio.crear(paciente.getId(), cita.getId(), new ResenaRequest((short) 5, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
        verify(resenaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Cita que ya tiene reseña → 409 sin guardar nada")
    void yaCalificada() {
        when(citaRepository.findById(cita.getId())).thenReturn(Optional.of(cita));
        when(resenaRepository.existsByCita_Id(cita.getId())).thenReturn(true);

        assertThatThrownBy(() -> servicio.crear(paciente.getId(), cita.getId(), new ResenaRequest((short) 5, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
        verify(resenaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Si otra petición se adelanta (UNIQUE de la BD) → 409")
    void carreraEntreDosPeticiones() {
        prepararCitaSinResena();
        when(resenaRepository.saveAndFlush(any(Resena.class))).thenThrow(new DataIntegrityViolationException("uq"));

        assertThatThrownBy(() -> servicio.crear(paciente.getId(), cita.getId(), new ResenaRequest((short) 5, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // Listar
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Lista: limita el tamaño de página a 50, corrige página negativa y abrevia el nombre")
    void listaConTopeDeTamano() {
        UUID id = perfil.getUsuarioId();
        when(perfilRepository.findById(id)).thenReturn(Optional.of(perfil));
        Resena resena = Resena.builder().id(7L).cita(cita).odontologo(perfil).paciente(paciente)
                .calificacion((short) 4).comentario("Muy bien").creadoEn(OffsetDateTime.now()).build();
        when(resenaRepository.delOdontologo(eq(id), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(resena), PageRequest.of(0, 50), 1));

        var pagina = servicio.delOdontologo(id, -3, 500);

        ArgumentCaptor<Pageable> pedido = ArgumentCaptor.forClass(Pageable.class);
        verify(resenaRepository).delOdontologo(eq(id), pedido.capture());
        assertThat(pedido.getValue().getPageNumber()).isZero();
        assertThat(pedido.getValue().getPageSize()).isEqualTo(50);
        assertThat(pagina.tamano()).isEqualTo(50);
        assertThat(pagina.totalElementos()).isEqualTo(1);
        assertThat(pagina.contenido()).hasSize(1);
        assertThat(pagina.contenido().get(0).paciente()).isEqualTo("Juan Q.");
    }

    @Test
    @DisplayName("Lista de un odontólogo no verificado → 404 sin consultar reseñas")
    void odontologoNoPublicable() {
        perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.PENDIENTE);
        when(perfilRepository.findById(perfil.getUsuarioId())).thenReturn(Optional.of(perfil));

        assertThatThrownBy(() -> servicio.delOdontologo(perfil.getUsuarioId(), 0, 20))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
        verifyNoInteractions(resenaRepository);
    }

    // ------------------------------------------------------------------
    // Nombre público
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Nombre público: primer nombre + inicial del último apellido")
    void nombrePublico() {
        assertThat(ResenaService.nombrePublico("Juan Carlos Pérez Quispe")).isEqualTo("Juan Q.");
        assertThat(ResenaService.nombrePublico("  maría   lópez ")).isEqualTo("maría L.");
        assertThat(ResenaService.nombrePublico("Madonna")).isEqualTo("Madonna");
        assertThat(ResenaService.nombrePublico("   ")).isEqualTo("Paciente");
        assertThat(ResenaService.nombrePublico(null)).isEqualTo("Paciente");
    }
}
package com.odontosystem.OdontoSystem.odontologos;

import com.odontosystem.OdontoSystem.entity.Especialidad;
import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ActualizarPerfilRequest;
import com.odontosystem.OdontoSystem.repository.EspecialidadRepository;
import com.odontosystem.OdontoSystem.repository.FotoConsultorioRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerfilOdontologoServiceTest {

    @Mock private PerfilOdontologoRepository perfilRepository;
    @Mock private EspecialidadRepository especialidadRepository;
    @Mock private ServicioRepository servicioRepository;
    @Mock private HorarioAtencionRepository horarioRepository;
    @Mock private FotoConsultorioRepository fotoRepository;

    private PerfilOdontologoService servicio;
    private final UUID id = UUID.randomUUID();
    private PerfilOdontologo perfil;

    @BeforeEach
    void setUp() {
        servicio = new PerfilOdontologoService(perfilRepository, especialidadRepository, servicioRepository,
                horarioRepository, fotoRepository);
        perfil = PerfilOdontologo.builder().usuarioId(id)
                .usuario(Usuario.builder().id(id).nombreCompleto("Ana Torres").build())
                .numeroColegiatura("12345").build();
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    @Test
    @DisplayName("Un perfil sin verificar no es público → 404")
    void perfilNoVerificado() {
        perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.PENDIENTE);
        when(perfilRepository.findById(id)).thenReturn(Optional.of(perfil));
        assertThatThrownBy(() -> servicio.perfilPublico(id)).satisfies(e -> assertThat(estado(e)).isEqualTo(404));
    }

    @Test
    @DisplayName("Perfil verificado: devuelve consultorio, especialidades y servicios")
    void perfilVerificado() {
        perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.VERIFICADO);
        perfil.setNombreConsultorio("Clínica Sonrisa");
        perfil.getEspecialidades().add(new Especialidad((short) 2, "Ortodoncia"));
        when(perfilRepository.findById(id)).thenReturn(Optional.of(perfil));
        when(servicioRepository.findByOdontologo_UsuarioIdAndEstaActivoTrue(id)).thenReturn(List.of());
        when(horarioRepository.findByOdontologo_UsuarioIdOrderByDiaSemanaAscHoraInicioAsc(id)).thenReturn(List.of());
        when(fotoRepository.findByOdontologoIdOrderByOrdenAsc(id)).thenReturn(List.of());

        var r = servicio.perfilPublico(id);

        assertThat(r.nombre()).isEqualTo("Ana Torres");
        assertThat(r.consultorio()).isEqualTo("Clínica Sonrisa");
        assertThat(r.especialidades()).extracting("nombre").containsExactly("Ortodoncia");
    }

    @Test
    @DisplayName("Actualizar: latitud sin longitud → 400; especialidad inexistente → 400")
    void validaActualizacion() {
        when(perfilRepository.findById(id)).thenReturn(Optional.of(perfil));
        assertThatThrownBy(() -> servicio.actualizarMiPerfil(id, new ActualizarPerfilRequest(
                null, null, null, new BigDecimal("-14.06"), null, null, null, null, null, null)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));

        when(especialidadRepository.findAllById(any())).thenReturn(List.of());
        assertThatThrownBy(() -> servicio.actualizarMiPerfil(id, new ActualizarPerfilRequest(
                null, null, null, null, null, List.of((short) 99), null, null, null, null)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
    }

    @Test
    @DisplayName("Actualizar: solo cambia lo enviado; texto vacío borra")
    void actualizacionParcial() {
        perfil.setNombreConsultorio("Viejo");
        perfil.setDireccionConsultorio("Av. Grau 123");
        when(perfilRepository.findById(id)).thenReturn(Optional.of(perfil));
        when(servicioRepository.findByOdontologo_UsuarioIdOrderByCreadoEnAsc(id)).thenReturn(List.of());
        when(horarioRepository.findByOdontologo_UsuarioIdOrderByDiaSemanaAscHoraInicioAsc(id)).thenReturn(List.of());
        when(fotoRepository.findByOdontologoIdOrderByOrdenAsc(id)).thenReturn(List.of());

        servicio.actualizarMiPerfil(id, new ActualizarPerfilRequest(
                " Clínica Nueva ", "", null, null, null, null, true, null, null, null));

        assertThat(perfil.getNombreConsultorio()).isEqualTo("Clínica Nueva");
        assertThat(perfil.getDireccionConsultorio()).isNull();
        assertThat(perfil.isRequiereConfirmacionManual()).isTrue();
    }
}

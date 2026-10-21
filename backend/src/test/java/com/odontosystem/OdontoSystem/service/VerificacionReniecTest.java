package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.ResultadoVerificacionCop;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.entity.VerificacionCop;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.VerificacionCopRepository;
import com.odontosystem.OdontoSystem.service.ClienteFactiliza.ConsultaDni;
import com.odontosystem.OdontoSystem.service.ClienteFactiliza.Estado;
import com.odontosystem.OdontoSystem.service.ClienteFactiliza.PersonaReniec;
import com.odontosystem.OdontoSystem.service.ColegioOdontologicoVerificacionService.EstadoVerificacionCop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Odontólogos OBSERVADO: el nombre del COP no coincide con el registrado y se confirma con el DNI en RENIEC. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerificacionReniecTest {

    static final OffsetDateTime AHORA = OffsetDateTime.of(2026, 10, 21, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock private PerfilOdontologoRepository perfiles;
    @Mock private VerificacionCopRepository verificaciones;
    @Mock private ColegioOdontologicoVerificacionService colegio;
    @Mock private ClienteFactiliza reniec;

    private VerificacionColegiaturaService servicio;
    private PerfilOdontologo perfil;

    @BeforeEach
    void setUp() {
        servicio = new VerificacionColegiaturaService(perfiles, verificaciones, colegio, reniec,
                Clock.fixed(AHORA.toInstant(), ZoneOffset.UTC));
        UUID id = UUID.randomUUID();
        // Se registró con otro nombre ("Anita Rojas") pero en el COP figura con su nombre completo
        perfil = PerfilOdontologo.builder().usuarioId(id).numeroColegiatura("12345")
                .usuario(Usuario.builder().id(id).nombreCompleto("Anita Rojas").tipoDocumento("DNI")
                        .numeroDocumento("45879632").build())
                .estadoVerificacion(EstadoVerificacionOdontologo.PENDIENTE).build();
        when(perfiles.save(any(PerfilOdontologo.class))).thenAnswer(i -> i.getArgument(0));
        when(perfiles.findById(id)).thenReturn(Optional.of(perfil));
        when(reniec.configurado()).thenReturn(true);
        when(colegio.verificar("12345")).thenReturn(new ColegioOdontologicoVerificacionService.ResultadoVerificacionCop(
                EstadoVerificacionCop.VERIFICADO_IDENTIDAD, "TORRES RIOS ANA MARIA", "LIMA"));
    }

    private static ConsultaDni encontrado(String nombres, String paterno, String materno) {
        return new ConsultaDni(Estado.ENCONTRADO, new PersonaReniec("45879632", nombres, paterno, materno), "ok");
    }

    private VerificacionCop bitacora() {
        ArgumentCaptor<VerificacionCop> log = ArgumentCaptor.forClass(VerificacionCop.class);
        verify(verificaciones).save(log.capture());
        return log.getValue();
    }

    @Test
    @DisplayName("RENIEC confirma que el DNI es del titular del COP → VERIFICADO; el DNI queda oculto en la bitácora")
    void confirmado() {
        when(reniec.consultarDni("45879632")).thenReturn(encontrado("ANA MARIA", "TORRES", "RIOS"));

        servicio.verificarYGuardar(perfil);

        assertThat(perfil.getEstadoVerificacion()).isEqualTo(EstadoVerificacionOdontologo.VERIFICADO);
        assertThat(perfil.isColegiaturaVerificada()).isTrue();
        assertThat(perfil.getNotaObservacion()).isNull();
        VerificacionCop log = bitacora();
        assertThat(log.getResultado()).isEqualTo(ResultadoVerificacionCop.VERIFICADO);
        assertThat(log.getDetalle()).contains(VerificacionColegiaturaService.MARCA_RENIEC).contains("****9632")
                .doesNotContain("45879632");
    }

    @Test
    @DisplayName("El DNI es de otra persona → sigue OBSERVADO con nota clara")
    void otraPersona() {
        when(reniec.consultarDni("45879632")).thenReturn(encontrado("CARLOS", "PEREZ", "GOMEZ"));

        servicio.verificarYGuardar(perfil);

        assertThat(perfil.getEstadoVerificacion()).isEqualTo(EstadoVerificacionOdontologo.OBSERVADO);
        assertThat(perfil.getNotaObservacion()).contains("RENIEC");
        assertThat(bitacora().getDetalle()).contains(VerificacionColegiaturaService.MARCA_RENIEC);
    }

    @Test
    @DisplayName("DNI no está en RENIEC → OBSERVADO pidiendo revisar el documento")
    void dniNoEncontrado() {
        when(reniec.consultarDni("45879632")).thenReturn(new ConsultaDni(Estado.NO_ENCONTRADO, null, "x"));

        servicio.verificarYGuardar(perfil);

        assertThat(perfil.getEstadoVerificacion()).isEqualTo(EstadoVerificacionOdontologo.OBSERVADO);
        assertThat(perfil.getNotaObservacion()).contains("RENIEC");
    }

    @Test
    @DisplayName("Sin cupo o Factiliza caído → OBSERVADO sin la marca de RENIEC (se reintentará)")
    void sinRespuesta() {
        when(reniec.consultarDni("45879632")).thenReturn(new ConsultaDni(Estado.SIN_CUPO, null, "límite"));

        servicio.verificarYGuardar(perfil);

        assertThat(perfil.getEstadoVerificacion()).isEqualTo(EstadoVerificacionOdontologo.OBSERVADO);
        assertThat(bitacora().getDetalle()).doesNotContain(VerificacionColegiaturaService.MARCA_RENIEC)
                .contains("se reintentará");
    }

    @Test
    @DisplayName("Carnet de extranjería o sin Factiliza: no se consulta RENIEC")
    void sinConsulta() {
        perfil.getUsuario().setTipoDocumento("CE");
        servicio.verificarYGuardar(perfil);
        assertThat(perfil.getEstadoVerificacion()).isEqualTo(EstadoVerificacionOdontologo.OBSERVADO);
        verify(reniec, never()).consultarDni(anyString());
    }

    @Test
    @DisplayName("Tarea: revisa los OBSERVADO sin respuesta de RENIEC y salta los ya revisados o desactivados")
    void tareaObservados() {
        UUID otroId = UUID.randomUUID();
        PerfilOdontologo yaRevisado = PerfilOdontologo.builder().usuarioId(otroId).numeroColegiatura("999")
                .estadoVerificacion(EstadoVerificacionOdontologo.OBSERVADO).build();
        PerfilOdontologo desactivado = PerfilOdontologo.builder().usuarioId(UUID.randomUUID()).numeroColegiatura("888")
                .desactivadoEn(AHORA).estadoVerificacion(EstadoVerificacionOdontologo.OBSERVADO).build();
        perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.OBSERVADO);
        when(perfiles.findByEstadoVerificacion(EstadoVerificacionOdontologo.OBSERVADO))
                .thenReturn(List.of(perfil, yaRevisado, desactivado));
        when(verificaciones.existsByOdontologo_UsuarioIdAndDetalleContaining(eq(otroId), anyString())).thenReturn(true);
        when(reniec.consultarDni("45879632")).thenReturn(encontrado("ANA MARIA", "TORRES", "RIOS"));

        assertThat(servicio.revisarObservadosConReniec()).isEqualTo(1);
        assertThat(perfil.getEstadoVerificacion()).isEqualTo(EstadoVerificacionOdontologo.VERIFICADO);
    }

    @Test
    @DisplayName("Tarea sin Factiliza configurado: no hace nada")
    void tareaSinFactiliza() {
        when(reniec.configurado()).thenReturn(false);
        assertThat(servicio.revisarObservadosConReniec()).isZero();
        verify(perfiles, never()).findByEstadoVerificacion(any());
    }

    @Test
    @DisplayName("Un OBSERVADO puede pedir el reintento desde su panel cuando Factiliza está activo")
    void reintentoObservado() {
        perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.OBSERVADO);
        when(verificaciones.findFirstByOdontologo_UsuarioIdOrderByEjecutadoEnDesc(perfil.getUsuarioId()))
                .thenReturn(Optional.empty());
        when(reniec.consultarDni("45879632")).thenReturn(encontrado("ANA MARIA", "TORRES", "RIOS"));

        var r = servicio.reintentarAhora(perfil.getUsuarioId());

        assertThat(r.estado()).isEqualTo(EstadoVerificacionOdontologo.VERIFICADO);
    }
}

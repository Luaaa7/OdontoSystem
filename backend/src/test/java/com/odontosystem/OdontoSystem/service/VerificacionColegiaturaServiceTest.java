package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.ResultadoVerificacionCop;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.entity.VerificacionCop;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.VerificacionCopRepository;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerificacionColegiaturaServiceTest {

    static final OffsetDateTime AHORA = OffsetDateTime.of(2026, 10, 19, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock private PerfilOdontologoRepository perfiles;
    @Mock private VerificacionCopRepository verificaciones;
    @Mock private ColegioOdontologicoVerificacionService colegio;

    private VerificacionColegiaturaService servicio;
    private PerfilOdontologo perfil;

    @BeforeEach
    void setUp() {
        servicio = new VerificacionColegiaturaService(perfiles, verificaciones, colegio, Clock.fixed(AHORA.toInstant(), ZoneOffset.UTC));
        UUID id = UUID.randomUUID();
        perfil = PerfilOdontologo.builder().usuarioId(id).numeroColegiatura("12345")
                .usuario(Usuario.builder().id(id).nombreCompleto("Ana María Torres Ríos").build())
                .estadoVerificacion(EstadoVerificacionOdontologo.PENDIENTE).build();
        when(perfiles.save(any(PerfilOdontologo.class))).thenAnswer(i -> i.getArgument(0));
        when(perfiles.findById(id)).thenReturn(Optional.of(perfil));
    }

    private void colegioResponde(EstadoVerificacionCop estado, String nombre) {
        when(colegio.verificar("12345")).thenReturn(
                new ColegioOdontologicoVerificacionService.ResultadoVerificacionCop(estado, nombre, "ICA"));
    }

    @Test
    @DisplayName("Encontrado con el mismo nombre (otro orden, sin tildes) → VERIFICADO y queda en la bitácora")
    void verificado() {
        colegioResponde(EstadoVerificacionCop.VERIFICADO_IDENTIDAD, "TORRES RIOS ANA MARIA");
        servicio.verificarYGuardar(perfil);
        assertThat(perfil.getEstadoVerificacion()).isEqualTo(EstadoVerificacionOdontologo.VERIFICADO);
        ArgumentCaptor<VerificacionCop> log = ArgumentCaptor.forClass(VerificacionCop.class);
        verify(verificaciones).save(log.capture());
        assertThat(log.getValue().getResultado()).isEqualTo(ResultadoVerificacionCop.VERIFICADO);
    }

    @Test
    @DisplayName("El COP no respondió → sigue PENDIENTE con aviso de reintento")
    void errorDeRed() {
        colegioResponde(EstadoVerificacionCop.ERROR_VERIFICACION, null);
        servicio.verificarYGuardar(perfil);
        assertThat(perfil.getEstadoVerificacion()).isEqualTo(EstadoVerificacionOdontologo.PENDIENTE);
        assertThat(perfil.getNotaObservacion()).contains("se reintentará");
    }

    @Test
    @DisplayName("Reintento automático: respeta la espera creciente y se detiene a los 5 fallos")
    void reintentoAutomatico() {
        when(perfiles.findByEstadoVerificacion(EstadoVerificacionOdontologo.PENDIENTE)).thenReturn(List.of(perfil));
        colegioResponde(EstadoVerificacionCop.VERIFICADO_IDENTIDAD, "ANA MARIA TORRES RIOS");

        // 2 fallos, el último hace 1 h: la espera es de 2 h → todavía no
        when(verificaciones.countByOdontologo_UsuarioIdAndResultado(perfil.getUsuarioId(), ResultadoVerificacionCop.ERROR)).thenReturn(2L);
        when(verificaciones.findFirstByOdontologo_UsuarioIdOrderByEjecutadoEnDesc(perfil.getUsuarioId()))
                .thenReturn(Optional.of(VerificacionCop.builder().ejecutadoEn(AHORA.minusHours(1)).build()));
        assertThat(servicio.reintentarPendientes()).isZero();

        // el último fue hace 3 h → se reintenta
        when(verificaciones.findFirstByOdontologo_UsuarioIdOrderByEjecutadoEnDesc(perfil.getUsuarioId()))
                .thenReturn(Optional.of(VerificacionCop.builder().ejecutadoEn(AHORA.minusHours(3)).build()));
        assertThat(servicio.reintentarPendientes()).isEqualTo(1);
        assertThat(perfil.getEstadoVerificacion()).isEqualTo(EstadoVerificacionOdontologo.VERIFICADO);

        // con 5 fallos ya no se reintenta solo
        perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.PENDIENTE);
        when(verificaciones.countByOdontologo_UsuarioIdAndResultado(perfil.getUsuarioId(), ResultadoVerificacionCop.ERROR)).thenReturn(5L);
        assertThat(servicio.reintentarPendientes()).isZero();
    }

    @Test
    @DisplayName("Espera: 1 h, 2 h, 4 h, 8 h")
    void esperaCreciente() {
        assertThat(VerificacionColegiaturaService.espera(1)).isEqualTo(Duration.ofHours(1));
        assertThat(VerificacionColegiaturaService.espera(2)).isEqualTo(Duration.ofHours(2));
        assertThat(VerificacionColegiaturaService.espera(4)).isEqualTo(Duration.ofHours(8));
    }

    @Test
    @DisplayName("Reintento manual: antes de 10 min → 429; si ya está VERIFICADO → 409")
    void reintentoManual() {
        when(verificaciones.findFirstByOdontologo_UsuarioIdOrderByEjecutadoEnDesc(perfil.getUsuarioId()))
                .thenReturn(Optional.of(VerificacionCop.builder().ejecutadoEn(AHORA.minusMinutes(3)).build()));
        assertThatThrownBy(() -> servicio.reintentarAhora(perfil.getUsuarioId()))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(429));
        verify(colegio, never()).verificar(anyString());

        perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.VERIFICADO);
        assertThatThrownBy(() -> servicio.reintentarAhora(perfil.getUsuarioId()))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    }
}

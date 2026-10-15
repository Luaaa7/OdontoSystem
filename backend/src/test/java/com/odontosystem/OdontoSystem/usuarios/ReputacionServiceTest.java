package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.citas.ParametrosSistema;
import com.odontosystem.OdontoSystem.entity.EstadoReputacion;
import com.odontosystem.OdontoSystem.entity.ReputacionPaciente;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.ReputacionPacienteRepository;
import com.odontosystem.OdontoSystem.usuarios.ReputacionService.ReputacionResponse;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class ReputacionServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-15T15:00:00Z");

    @Mock private ReputacionPacienteRepository reputaciones;
    @Mock private ParametrosSistema parametros;

    private ReputacionService servicio;
    private final UUID paciente = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        servicio = new ReputacionService(reputaciones, parametros, Clock.fixed(AHORA, ZoneOffset.UTC));
        when(parametros.entero(eq(ParametrosSistema.LIMITE_STRIKES), eq(3))).thenReturn(3);
        when(parametros.entero(eq(ReputacionService.DIAS_SUSPENSION), eq(30))).thenReturn(30);
        when(reputaciones.save(any(ReputacionPaciente.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void conReputacion(int strikes, EstadoReputacion estado, OffsetDateTime restringidoHasta) {
        when(reputaciones.findById(paciente)).thenReturn(Optional.of(ReputacionPaciente.builder()
                .usuarioId(paciente).cantidadStrikes((short) strikes).estado(estado)
                .restringidoHasta(restringidoHasta).build()));
    }

    @Test
    @DisplayName("Sin registro de reputación: 0 strikes, EXCELENTE y puede reservar")
    void sinRegistro() {
        ReputacionResponse r = servicio.deMiCuenta(paciente);
        assertThat(r.strikes()).isZero();
        assertThat(r.limiteStrikes()).isEqualTo(3);
        assertThat(r.estado()).isEqualTo(EstadoReputacion.EXCELENTE);
        assertThat(r.puedeReservar()).isTrue();
    }

    @Test
    @DisplayName("Con 2 strikes (REGULAR) todavía puede reservar")
    void regular() {
        conReputacion(2, EstadoReputacion.REGULAR, null);
        ReputacionResponse r = servicio.deMiCuenta(paciente);
        assertThat(r.strikes()).isEqualTo((short) 2);
        assertThat(r.puedeReservar()).isTrue();
    }

    @Test
    @DisplayName("BLOQUEADO no puede reservar")
    void bloqueado() {
        conReputacion(3, EstadoReputacion.BLOQUEADO, null);
        assertThat(servicio.deMiCuenta(paciente).puedeReservar()).isFalse();
    }

    @Test
    @DisplayName("Una restricción temporal vigente impide reservar; una vencida no")
    void restriccionTemporal() {
        conReputacion(1, EstadoReputacion.REGULAR, OffsetDateTime.ofInstant(AHORA.plusSeconds(3600), ZoneOffset.UTC));
        assertThat(servicio.deMiCuenta(paciente).puedeReservar()).isFalse();

        conReputacion(1, EstadoReputacion.REGULAR, OffsetDateTime.ofInstant(AHORA.minusSeconds(3600), ZoneOffset.UTC));
        assertThat(servicio.deMiCuenta(paciente).puedeReservar()).isTrue();
    }

    private static OffsetDateTime enHoras(long horas) {
        return OffsetDateTime.ofInstant(AHORA.plusSeconds(horas * 3600), ZoneOffset.UTC);
    }

    private ReputacionPaciente guardada() {
        ArgumentCaptor<ReputacionPaciente> captor = ArgumentCaptor.forClass(ReputacionPaciente.class);
        verify(reputaciones).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Primer strike: crea el registro en REGULAR, sin suspensión")
    void primerStrike() {
        when(reputaciones.findById(paciente)).thenReturn(Optional.empty());
        Usuario u = Usuario.builder().id(paciente).rol(RolUsuario.PACIENTE).build();
        ReputacionPaciente r = servicio.sumarStrike(u);
        assertThat(r.getCantidadStrikes()).isEqualTo((short) 1);
        assertThat(r.getEstado()).isEqualTo(EstadoReputacion.REGULAR);
        assertThat(r.getRestringidoHasta()).isNull();
    }

    @Test
    @DisplayName("Tercer strike: BLOQUEADO y suspendido 30 días")
    void tercerStrikeSuspende() {
        conReputacion(2, EstadoReputacion.REGULAR, null);
        ReputacionPaciente r = servicio.sumarStrike(Usuario.builder().id(paciente).build());
        assertThat(r.getCantidadStrikes()).isEqualTo((short) 3);
        assertThat(r.getEstado()).isEqualTo(EstadoReputacion.BLOQUEADO);
        assertThat(r.getRestringidoHasta()).isEqualTo(enHoras(30 * 24));
    }

    @Test
    @DisplayName("Suspendido: reservar da 403 con la fecha; cumplida la suspensión vuelve a 0 strikes")
    void suspensionYReinicio() {
        conReputacion(3, EstadoReputacion.BLOQUEADO, enHoras(48));
        assertThatThrownBy(() -> servicio.exigirQuePuedaReservar(paciente))
                .hasMessageContaining("hasta el");
        verify(reputaciones, never()).save(any());

        conReputacion(3, EstadoReputacion.BLOQUEADO, enHoras(-1));
        servicio.exigirQuePuedaReservar(paciente);
        ReputacionPaciente r = guardada();
        assertThat(r.getCantidadStrikes()).isZero();
        assertThat(r.getEstado()).isEqualTo(EstadoReputacion.EXCELENTE);
        assertThat(r.getRestringidoHasta()).isNull();
    }

    @Test
    @DisplayName("Asistir o una apelación aprobada quita un strike y levanta la suspensión; en 0 no hace nada")
    void quitarStrike() {
        conReputacion(3, EstadoReputacion.BLOQUEADO, enHoras(100));
        servicio.quitarStrike(paciente);
        ReputacionPaciente r = guardada();
        assertThat(r.getCantidadStrikes()).isEqualTo((short) 2);
        assertThat(r.getEstado()).isEqualTo(EstadoReputacion.REGULAR);
        assertThat(r.getRestringidoHasta()).isNull();
    }

    @Test
    @DisplayName("Con 0 strikes, quitar uno no guarda nada")
    void quitarEnCero() {
        conReputacion(0, EstadoReputacion.EXCELENTE, null);
        servicio.quitarStrike(paciente);
        verify(reputaciones, never()).save(any());
    }
}

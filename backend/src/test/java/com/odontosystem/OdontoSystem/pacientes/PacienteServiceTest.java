package com.odontosystem.OdontoSystem.pacientes;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.pacientes.PacienteService.PacienteDetalle;
import com.odontosystem.OdontoSystem.pacientes.PacienteService.PacienteResumen;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** "Mis pacientes" y el detalle de un paciente para el historial clínico. Hoy es el 1/11/2026 al mediodía. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PacienteServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-01T17:00:00Z");

    @Mock private CitaRepository citas;

    private PacienteService servicio;
    private final UUID odontologoId = UUID.randomUUID();
    private Usuario nadia;
    private Usuario marisol;

    @BeforeEach
    void setUp() {
        servicio = new PacienteService(citas, Clock.fixed(AHORA, ZoneOffset.UTC));
        nadia = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Nadia Chávez Huamán")
                .correo("nadia@correo.pe").telefono("+51900000001").build();
        marisol = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Marisol Salazar Quispe")
                .correo("marisol@correo.pe").telefono("+51900000002").build();
    }

    private static OffsetDateTime dias(long d) {
        return OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC).plusDays(d);
    }

    private static Cita cita(Usuario paciente, EstadoCita estado, OffsetDateTime inicio) {
        return Cita.builder().id(UUID.randomUUID()).paciente(paciente).estado(estado).inicioProgramado(inicio).build();
    }

    @Test
    @DisplayName("Lista a todos los pacientes, aunque solo tengan citas pasadas, con totales, última atención y próxima cita")
    void listaTodosLosPacientes() {
        when(citas.todasDelOdontologo(odontologoId)).thenReturn(List.of(
                cita(marisol, EstadoCita.COMPLETADA, dias(-90)),
                cita(marisol, EstadoCita.COMPLETADA, dias(-30)),
                cita(nadia, EstadoCita.COMPLETADA, dias(-60)),
                cita(nadia, EstadoCita.INASISTENCIA_PENALIZADA, dias(-20)),
                cita(nadia, EstadoCita.CANCELADA_CON_REEMBOLSO, dias(1)),
                cita(nadia, EstadoCita.CONFIRMADA, dias(3))));

        List<PacienteResumen> lista = servicio.deOdontologo(odontologoId);

        assertThat(lista).extracting(PacienteResumen::nombreCompleto)
                .containsExactly("Nadia Chávez Huamán", "Marisol Salazar Quispe"); // primero quien tiene cita próxima
        PacienteResumen n = lista.get(0);
        assertThat(n.totalCitas()).isEqualTo(4);
        assertThat(n.citasAtendidas()).isEqualTo(1);
        assertThat(n.inasistencias()).isEqualTo(1);
        assertThat(n.ultimaAtencion()).isEqualTo(dias(-60));
        assertThat(n.proximaCita()).isEqualTo(dias(3)); // la cancelada de mañana no cuenta
        PacienteResumen m = lista.get(1);
        assertThat(m.citasAtendidas()).isEqualTo(2);
        assertThat(m.ultimaAtencion()).isEqualTo(dias(-30));
        assertThat(m.proximaCita()).isNull();
    }

    @Test
    @DisplayName("Detalle: separa nombre y apellidos, y deja alergias y antecedentes en null (nunca inventados)")
    void detalleSinDatosInventados() {
        when(citas.delPacienteConOdontologo(odontologoId, nadia.getId()))
                .thenReturn(List.of(cita(nadia, EstadoCita.COMPLETADA, dias(-60))));

        PacienteDetalle d = servicio.detalle(odontologoId, nadia.getId());

        assertThat(d.id()).isEqualTo(nadia.getId());
        assertThat(d.nombre()).isEqualTo("Nadia");
        assertThat(d.apellido()).isEqualTo("Chávez Huamán");
        assertThat(d.nombreCompleto()).isEqualTo("Nadia Chávez Huamán");
        assertThat(d.alergias()).isNull();
        assertThat(d.antecedentes()).isNull();
        assertThat(d.citasAtendidas()).isEqualTo(1);
    }

    @Test
    @DisplayName("Un paciente que nunca tuvo cita con el odontólogo da 404")
    void pacienteAjenoDa404() {
        when(citas.delPacienteConOdontologo(odontologoId, marisol.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> servicio.detalle(odontologoId, marisol.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("Separa el nombre aunque venga con espacios de más o con un solo nombre")
    void separaNombre() {
        assertThat(PacienteService.separarNombre("  Ana   Torres ")).containsExactly("Ana", "Torres");
        assertThat(PacienteService.separarNombre("Ana")).containsExactly("Ana", "");
        assertThat(PacienteService.separarNombre(null)).containsExactly("", "");
    }
}

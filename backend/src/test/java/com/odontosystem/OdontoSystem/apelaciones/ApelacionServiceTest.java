package com.odontosystem.OdontoSystem.apelaciones;

import com.odontosystem.OdontoSystem.apelaciones.ApelacionDtos.ApelacionResponse;
import com.odontosystem.OdontoSystem.archivos.ArchivoService;
import com.odontosystem.OdontoSystem.archivos.ArchivoService.ArchivoSubido;
import com.odontosystem.OdontoSystem.citas.ParametrosSistema;
import com.odontosystem.OdontoSystem.citas.ReembolsoService;
import com.odontosystem.OdontoSystem.entity.Apelacion;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoApelacion;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoReputacion;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.ReputacionPaciente;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.ApelacionRepository;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.ReputacionPacienteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApelacionServiceTest {

    private static final OffsetDateTime AHORA = OffsetDateTime.of(2026, 10, 20, 10, 0, 0, 0, ZoneOffset.ofHours(-5));
    private static final Clock RELOJ = Clock.fixed(AHORA.toInstant(), ZoneOffset.UTC);

    @Mock private ApelacionRepository apelaciones;
    @Mock private CitaRepository citas;
    @Mock private ReputacionPacienteRepository reputaciones;
    @Mock private ArchivoService archivos;
    @Mock private ReembolsoService reembolsos;
    @Mock private ParametrosSistema parametros;

    private ApelacionService servicio;
    private Usuario paciente;
    private PerfilOdontologo odontologo;
    private final MockMultipartFile certificado =
            new MockMultipartFile("certificado", "certificado.pdf", "application/pdf", "%PDF-1.4".getBytes());

    @BeforeEach
    void setUp() {
        servicio = new ApelacionService(apelaciones, citas,
                new com.odontosystem.OdontoSystem.usuarios.ReputacionService(reputaciones, parametros, RELOJ),
                archivos, reembolsos, RELOJ);
        paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Lucía Ramos").build();
        odontologo = PerfilOdontologo.builder().usuarioId(UUID.randomUUID()).build();
        when(parametros.entero(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(archivos.subirDocumentoPrivado(any(), anyString()))
                .thenReturn(new ArchivoSubido("https://res.cloudinary.com/x/image/authenticated/v1/cert.pdf", "cert"));
        when(apelaciones.saveAndFlush(any(Apelacion.class))).thenAnswer(i -> {
            Apelacion a = i.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });
    }

    private Cita cita(EstadoCita estado, OffsetDateTime inicio) {
        Cita c = Cita.builder().id(UUID.randomUUID()).paciente(paciente).odontologo(odontologo).estado(estado)
                .inicioProgramado(inicio).servicio(Servicio.builder().titulo("Limpieza").build()).build();
        when(citas.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private Apelacion pendiente(Cita c) {
        Apelacion a = Apelacion.builder().id(UUID.randomUUID()).cita(c).urlCertificado("u").build();
        when(apelaciones.conDetalle(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    @Test
    @DisplayName("Apelar una inasistencia de ayer: sube el certificado privado y queda PENDIENTE")
    void apelar() {
        Cita c = cita(EstadoCita.INASISTENCIA_PENALIZADA, AHORA.minusDays(1));

        ApelacionResponse r = servicio.apelar(paciente.getId(), c.getId(), certificado);

        assertThat(r.estado()).isEqualTo(EstadoApelacion.PENDIENTE);
        verify(archivos).subirDocumentoPrivado(certificado, "apelaciones/" + paciente.getId());
    }

    @Test
    @DisplayName("Solo se apelan inasistencias (409) y dentro de 7 días (409), sin subir nada")
    void reglasParaApelar() {
        Cita atendida = cita(EstadoCita.COMPLETADA, AHORA.minusDays(1));
        assertThatThrownBy(() -> servicio.apelar(paciente.getId(), atendida.getId(), certificado))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));

        Cita antigua = cita(EstadoCita.INASISTENCIA_PENALIZADA, AHORA.minusDays(8));
        assertThatThrownBy(() -> servicio.apelar(paciente.getId(), antigua.getId(), certificado))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));

        verify(archivos, never()).subirDocumentoPrivado(any(), anyString());
    }

    @Test
    @DisplayName("Una apelación por cita (409); una cita ajena (404)")
    void duplicadaYAjena() {
        Cita c = cita(EstadoCita.INASISTENCIA_PENALIZADA, AHORA.minusDays(1));
        when(apelaciones.existsByCita_Id(c.getId())).thenReturn(true);
        assertThatThrownBy(() -> servicio.apelar(paciente.getId(), c.getId(), certificado))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));

        assertThatThrownBy(() -> servicio.apelar(UUID.randomUUID(), c.getId(), certificado))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
    }

    @Test
    @DisplayName("Si la BD rechaza la apelación, se borra el certificado que se acababa de subir")
    void borraArchivoSiFallaLaBd() {
        Cita c = cita(EstadoCita.INASISTENCIA_PENALIZADA, AHORA.minusDays(1));
        when(apelaciones.saveAndFlush(any(Apelacion.class))).thenThrow(new DataIntegrityViolationException("duplicada"));

        assertThatThrownBy(() -> servicio.apelar(paciente.getId(), c.getId(), certificado))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(archivos).eliminar("https://res.cloudinary.com/x/image/authenticated/v1/cert.pdf");
    }

    @Test
    @DisplayName("Aprobada: el paciente bloqueado (3 strikes) baja a 2 = REGULAR y se le devuelve el depósito")
    void aprobar() {
        Cita c = cita(EstadoCita.INASISTENCIA_PENALIZADA, AHORA.minusDays(1));
        Apelacion a = pendiente(c);
        ReputacionPaciente rep = ReputacionPaciente.builder().usuarioId(paciente.getId()).cantidadStrikes((short) 3)
                .estado(EstadoReputacion.BLOQUEADO).restringidoHasta(AHORA.plusDays(30)).build();
        when(reputaciones.findById(paciente.getId())).thenReturn(Optional.of(rep));

        ApelacionResponse r = servicio.resolver(odontologo.getUsuarioId(), a.getId(), true, " Certificado válido ");

        assertThat(r.estado()).isEqualTo(EstadoApelacion.APROBADA);
        assertThat(r.notaResolucion()).isEqualTo("Certificado válido");
        assertThat(rep.getCantidadStrikes()).isEqualTo((short) 2);
        assertThat(rep.getEstado()).isEqualTo(EstadoReputacion.REGULAR);
        assertThat(rep.getRestringidoHasta()).isNull();
        assertThat(a.getResueltoPorOdontologoId()).isEqualTo(odontologo.getUsuarioId());
        verify(reembolsos).reembolsarDeposito(c.getId(), "apelacion:" + c.getId(), "Apelación aprobada: inasistencia justificada");
    }

    @Test
    @DisplayName("Rechazada: los strikes y el depósito quedan igual")
    void rechazar() {
        Cita c = cita(EstadoCita.INASISTENCIA_PENALIZADA, AHORA.minusDays(1));
        Apelacion a = pendiente(c);
        ReputacionPaciente rep = ReputacionPaciente.builder().usuarioId(paciente.getId()).cantidadStrikes((short) 1)
                .estado(EstadoReputacion.REGULAR).build();
        when(reputaciones.findById(paciente.getId())).thenReturn(Optional.of(rep));

        ApelacionResponse r = servicio.resolver(odontologo.getUsuarioId(), a.getId(), false, null);

        assertThat(r.estado()).isEqualTo(EstadoApelacion.RECHAZADA);
        assertThat(rep.getCantidadStrikes()).isEqualTo((short) 1);
        verify(reembolsos, never()).reembolsarDeposito(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Solo la resuelve el odontólogo de la cita (404) y una sola vez (409)")
    void resolverPermisos() {
        Cita c = cita(EstadoCita.INASISTENCIA_PENALIZADA, AHORA.minusDays(1));
        Apelacion a = pendiente(c);

        assertThatThrownBy(() -> servicio.resolver(UUID.randomUUID(), a.getId(), true, null))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));

        a.setEstado(EstadoApelacion.RECHAZADA);
        assertThatThrownBy(() -> servicio.resolver(odontologo.getUsuarioId(), a.getId(), true, null))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
    }

    @Test
    @DisplayName("Sin revisar en 7 días: se aprueba sola, −1 strike y devolución del depósito")
    void aprobadaSola() {
        Cita c = cita(EstadoCita.INASISTENCIA_PENALIZADA, AHORA.minusDays(9));
        Apelacion a = Apelacion.builder().id(UUID.randomUUID()).cita(c).urlCertificado("u")
                .creadoEn(AHORA.minusDays(8)).build();
        when(apelaciones.sinRevisarDesde(org.mockito.ArgumentMatchers.eq(EstadoApelacion.PENDIENTE),
                org.mockito.ArgumentMatchers.eq(AHORA.minusDays(7).atZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime())))
                .thenReturn(java.util.List.of(a));
        ReputacionPaciente rep = ReputacionPaciente.builder().usuarioId(paciente.getId()).cantidadStrikes((short) 1)
                .estado(EstadoReputacion.REGULAR).build();
        when(reputaciones.findById(paciente.getId())).thenReturn(Optional.of(rep));

        assertThat(servicio.aprobarSinRevisar()).isEqualTo(1);

        assertThat(a.getEstado()).isEqualTo(EstadoApelacion.APROBADA);
        assertThat(a.getNotaResolucion()).contains("automáticamente");
        assertThat(a.getResueltoPorOdontologoId()).isNull();
        assertThat(rep.getCantidadStrikes()).isZero();
        verify(reembolsos).reembolsarDeposito(org.mockito.ArgumentMatchers.eq(c.getId()),
                org.mockito.ArgumentMatchers.eq("apelacion:" + c.getId()), anyString());
    }
}

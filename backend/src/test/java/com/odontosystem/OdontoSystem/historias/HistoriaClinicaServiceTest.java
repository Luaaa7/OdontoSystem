package com.odontosystem.OdontoSystem.historias;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.HistoriaClinica;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.historias.HistoriaDtos.HistoriaRequest;
import com.odontosystem.OdontoSystem.historias.HistoriaDtos.HistoriaResponse;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.HistoriaClinicaRepository;
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

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Usa el cifrado real (AES-256-GCM) con una clave de prueba: así se comprueba que la BD solo recibe bytes cifrados. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HistoriaClinicaServiceTest {

    /** 32 bytes de prueba (solo para tests, nunca en producción). */
    private static final String CLAVE_PRUEBA = Base64.getEncoder()
            .encodeToString("clave-de-prueba-32-bytes-exacto!".getBytes(StandardCharsets.UTF_8));

    @Mock private HistoriaClinicaRepository historias;
    @Mock private CitaRepository citas;

    private CifradoClinico cifrado;
    private HistoriaClinicaService servicio;
    private Usuario paciente;
    private PerfilOdontologo odontologo;

    @BeforeEach
    void setUp() {
        cifrado = new CifradoClinico(CLAVE_PRUEBA);
        servicio = new HistoriaClinicaService(historias, citas, cifrado);
        paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Lucía Ramos Peña").build();
        Usuario usuarioOdontologo = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Dra. Ana Torres").build();
        odontologo = PerfilOdontologo.builder().usuarioId(usuarioOdontologo.getId()).usuario(usuarioOdontologo).build();
        when(historias.saveAndFlush(any(HistoriaClinica.class))).thenAnswer(i -> {
            HistoriaClinica h = i.getArgument(0);
            h.setId(1L);
            h.setCreadoEn(OffsetDateTime.now());
            return h;
        });
    }

    private Cita cita(EstadoCita estado) {
        Cita c = Cita.builder().id(UUID.randomUUID()).paciente(paciente).odontologo(odontologo).estado(estado)
                .inicioProgramado(OffsetDateTime.now().minusHours(2)).build();
        when(citas.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    private HistoriaClinica guardada(Cita c) {
        return HistoriaClinica.builder().id(1L).cita(c).pacienteId(paciente.getId())
                .creadoPorOdontologoId(odontologo.getUsuarioId())
                .diagnosticoCifrado(cifrado.cifrar("Caries en pieza 36", HistoriaClinicaService.contexto(c.getId(), "diagnostico")))
                .tratamientoCifrado(cifrado.cifrar("Resina compuesta", HistoriaClinicaService.contexto(c.getId(), "tratamiento")))
                .creadoEn(OffsetDateTime.now()).build();
    }

    @Test
    @DisplayName("El odontólogo registra la historia de una cita atendida: a la BD llegan solo bytes cifrados")
    void registraCifrado() {
        Cita c = cita(EstadoCita.COMPLETADA);

        HistoriaResponse r = servicio.registrar(odontologo.getUsuarioId(), c.getId(),
                new HistoriaRequest("  Caries en pieza 36 ", "Resina compuesta", "Control en 6 meses"));

        ArgumentCaptor<HistoriaClinica> captor = ArgumentCaptor.forClass(HistoriaClinica.class);
        verify(historias).saveAndFlush(captor.capture());
        HistoriaClinica h = captor.getValue();
        assertThat(h.getPacienteId()).isEqualTo(paciente.getId());
        assertThat(h.getCreadoPorOdontologoId()).isEqualTo(odontologo.getUsuarioId());
        assertThat(new String(h.getDiagnosticoCifrado(), StandardCharsets.UTF_8)).doesNotContain("Caries");
        assertThat(h.getNotasClinicasCifradas()).isNotNull();
        assertThat(r.diagnostico()).isEqualTo("Caries en pieza 36");
        assertThat(r.tratamiento()).isEqualTo("Resina compuesta");
        assertThat(r.notas()).isEqualTo("Control en 6 meses");
        assertThat(r.odontologo()).isEqualTo("Dra. Ana Torres");
    }

    @Test
    @DisplayName("Notas vacías no se guardan")
    void notasVaciasSonNull() {
        Cita c = cita(EstadoCita.COMPLETADA);
        HistoriaResponse r = servicio.registrar(odontologo.getUsuarioId(), c.getId(),
                new HistoriaRequest("Gingivitis", "Profilaxis", "   "));
        assertThat(r.notas()).isNull();
    }

    @Test
    @DisplayName("Solo después de atender la cita (409 si aún está confirmada)")
    void exigeCitaCompletada() {
        Cita c = cita(EstadoCita.CONFIRMADA);
        assertThatThrownBy(() -> servicio.registrar(odontologo.getUsuarioId(), c.getId(),
                new HistoriaRequest("x", "y", null)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
        verify(historias, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Otro odontólogo no puede escribir la historia (404)")
    void otroOdontologoNo() {
        Cita c = cita(EstadoCita.COMPLETADA);
        assertThatThrownBy(() -> servicio.registrar(UUID.randomUUID(), c.getId(), new HistoriaRequest("x", "y", null)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
    }

    @Test
    @DisplayName("Una sola historia por cita (409)")
    void unaPorCita() {
        Cita c = cita(EstadoCita.COMPLETADA);
        when(historias.existsByCita_Id(c.getId())).thenReturn(true);
        assertThatThrownBy(() -> servicio.registrar(odontologo.getUsuarioId(), c.getId(), new HistoriaRequest("x", "y", null)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
    }

    @Test
    @DisplayName("Sin clave configurada responde 503 y no guarda nada")
    void sinClave503() {
        servicio = new HistoriaClinicaService(historias, citas, new CifradoClinico(""));
        Cita c = cita(EstadoCita.COMPLETADA);
        assertThatThrownBy(() -> servicio.registrar(odontologo.getUsuarioId(), c.getId(), new HistoriaRequest("x", "y", null)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(503));
        verify(historias, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("La leen el paciente y el odontólogo autor; un tercero recibe 404")
    void accesoALaHistoria() {
        Cita c = cita(EstadoCita.COMPLETADA);
        when(historias.deCita(c.getId())).thenReturn(Optional.of(guardada(c)));

        assertThat(servicio.deCita(paciente.getId(), c.getId()).diagnostico()).isEqualTo("Caries en pieza 36");
        assertThat(servicio.deCita(odontologo.getUsuarioId(), c.getId()).tratamiento()).isEqualTo("Resina compuesta");
        assertThatThrownBy(() -> servicio.deCita(UUID.randomUUID(), c.getId()))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
    }

    @Test
    @DisplayName("Mi historial clínico (paciente) descifra cada historia")
    void historialDelPaciente() {
        Cita c = cita(EstadoCita.COMPLETADA);
        when(historias.delPaciente(paciente.getId())).thenReturn(List.of(guardada(c)));
        List<HistoriaResponse> lista = servicio.delPaciente(paciente.getId());
        assertThat(lista).singleElement().satisfies(h -> assertThat(h.diagnostico()).isEqualTo("Caries en pieza 36"));
    }

    // ---- cifrado ----

    @Test
    @DisplayName("Cifrar y descifrar devuelve el texto; el mismo texto cifrado dos veces da bytes distintos")
    void idaYVuelta() {
        byte[] a = cifrado.cifrar("Endodoncia ñ á", "ctx");
        byte[] b = cifrado.cifrar("Endodoncia ñ á", "ctx");
        assertThat(a).isNotEqualTo(b);
        assertThat(cifrado.descifrar(a, "ctx")).isEqualTo("Endodoncia ñ á");
    }

    @Test
    @DisplayName("Copiar el dato a otra cita/campo, alterarlo o usar otra clave hace fallar el descifrado")
    void detectaManipulacion() {
        byte[] datos = cifrado.cifrar("Caries", "historia:1:diagnostico");
        assertThatThrownBy(() -> cifrado.descifrar(datos, "historia:2:diagnostico")).isInstanceOf(IllegalStateException.class);

        byte[] alterado = datos.clone();
        alterado[alterado.length - 1] ^= 1;
        assertThatThrownBy(() -> cifrado.descifrar(alterado, "historia:1:diagnostico")).isInstanceOf(IllegalStateException.class);

        CifradoClinico otraClave = new CifradoClinico(Base64.getEncoder().encodeToString(new byte[32]));
        assertThatThrownBy(() -> otraClave.descifrar(datos, "historia:1:diagnostico")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Una clave que no es Base64 de 32 bytes impide arrancar")
    void claveInvalida() {
        assertThatThrownBy(() -> new CifradoClinico("no-es-base64!!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CifradoClinico(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}

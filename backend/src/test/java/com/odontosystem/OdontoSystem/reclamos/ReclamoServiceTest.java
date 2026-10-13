package com.odontosystem.OdontoSystem.reclamos;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoReclamo;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Reclamo;
import com.odontosystem.OdontoSystem.entity.TipoReclamo;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.reclamos.ReclamoDtos.ReclamoRequest;
import com.odontosystem.OdontoSystem.reclamos.ReclamoDtos.ReclamoResponse;
import com.odontosystem.OdontoSystem.reclamos.ReclamoDtos.RespuestaRequest;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.ReclamoRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class ReclamoServiceTest {

    /** Lunes 12/10/2026 10:00 en Lima. */
    private static final Instant AHORA = OffsetDateTime.of(2026, 10, 12, 10, 0, 0, 0, ZoneOffset.ofHours(-5)).toInstant();

    @Mock private ReclamoRepository reclamos;
    @Mock private CitaRepository citas;
    @Mock private UsuarioRepository usuarios;
    @Mock private ApplicationEventPublisher eventos;

    private ReclamoService servicio;
    private Usuario paciente;
    private Usuario usuarioOdontologo;
    private PerfilOdontologo odontologo;

    @BeforeEach
    void setUp() {
        servicio = new ReclamoService(reclamos, citas, usuarios, eventos, Clock.fixed(AHORA, ZoneOffset.UTC));
        paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Lucía Ramos Peña").correo("lucia@correo.pe").build();
        usuarioOdontologo = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Dra. Ana Torres").build();
        odontologo = PerfilOdontologo.builder().usuarioId(usuarioOdontologo.getId()).usuario(usuarioOdontologo).build();
        when(usuarios.findById(paciente.getId())).thenReturn(Optional.of(paciente));
        when(usuarios.findById(usuarioOdontologo.getId())).thenReturn(Optional.of(usuarioOdontologo));
        when(reclamos.saveAndFlush(any(Reclamo.class))).thenAnswer(i -> {
            Reclamo r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.fromString("1a2b3c4d-0000-0000-0000-000000000000"));
            return r;
        });
    }

    private Cita cita() {
        Cita c = Cita.builder().id(UUID.randomUUID()).paciente(paciente).odontologo(odontologo)
                .estado(EstadoCita.COMPLETADA).build();
        when(citas.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private Reclamo guardado(Usuario autor, Cita c, EstadoReclamo estado) {
        Reclamo r = Reclamo.builder().id(UUID.randomUUID()).usuario(autor).cita(c).tipo(TipoReclamo.RECLAMO)
                .estado(estado).asunto("Cobro").descripcion("Me cobraron dos veces")
                .creadoEn(OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC)).build();
        when(reclamos.conDetalle(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    @Test
    @DisplayName("El paciente reclama sobre su cita: lo atiende su odontólogo, con código y plazo de 15 días hábiles")
    void reclamoSobreCita() {
        Cita c = cita();

        ReclamoResponse r = servicio.registrar(paciente.getId(),
                new ReclamoRequest(TipoReclamo.RECLAMO, "  Me atendieron tarde ", "Esperé una hora", c.getId()));

        assertThat(r.codigo()).isEqualTo("REC-1A2B3C4D");
        assertThat(r.estado()).isEqualTo(EstadoReclamo.ABIERTO);
        assertThat(r.asunto()).isEqualTo("Me atendieron tarde");
        assertThat(r.atiende()).isEqualTo("ODONTOLOGO");
        assertThat(r.odontologo()).isEqualTo("Dra. Ana Torres");
        // lunes 12/10 + 15 días hábiles = lunes 2/11
        assertThat(r.responderAntes()).isEqualTo(LocalDate.of(2026, 11, 2));
        verify(eventos).publishEvent(any(ReclamoRegistrado.class));
    }

    @Test
    @DisplayName("Sin cita (problema de la plataforma) lo atiende soporte")
    void sinCitaVaASoporte() {
        ReclamoResponse r = servicio.registrar(paciente.getId(),
                new ReclamoRequest(TipoReclamo.INCIDENCIA, "No carga el mapa", "En el buscador", null));
        assertThat(r.atiende()).isEqualTo("SOPORTE");
        assertThat(r.citaId()).isNull();
    }

    @Test
    @DisplayName("Un reclamo del odontólogo sobre su cita no se lo atiende él mismo: va a soporte")
    void odontologoReclamaVaASoporte() {
        Cita c = cita();
        ReclamoResponse r = servicio.registrar(usuarioOdontologo.getId(),
                new ReclamoRequest(TipoReclamo.INCIDENCIA, "Paciente agresivo", "Detalle", c.getId()));
        assertThat(r.atiende()).isEqualTo("SOPORTE");
    }

    @Test
    @DisplayName("No se puede asociar la cita de otra persona (404)")
    void citaAjena() {
        Cita c = cita();
        Usuario otro = Usuario.builder().id(UUID.randomUUID()).build();
        when(usuarios.findById(otro.getId())).thenReturn(Optional.of(otro));
        assertThatThrownBy(() -> servicio.registrar(otro.getId(),
                new ReclamoRequest(TipoReclamo.RECLAMO, "x", "y", c.getId())))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
        verify(reclamos, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Máximo 5 reclamos sin resolver por usuario (409)")
    void limiteSinResolver() {
        when(reclamos.countByUsuario_IdAndEstadoIn(eq(paciente.getId()), any())).thenReturn(5L);
        assertThatThrownBy(() -> servicio.registrar(paciente.getId(),
                new ReclamoRequest(TipoReclamo.CONSULTA, "x", "y", null)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
    }

    @Test
    @DisplayName("El odontólogo responde y lo marca resuelto: se avisa al paciente")
    void odontologoResponde() {
        Reclamo r = guardado(paciente, cita(), EstadoReclamo.ABIERTO);

        ReclamoResponse resp = servicio.responder(usuarioOdontologo.getId(), r.getId(),
                new RespuestaRequest("  Disculpa la demora, te descontamos el depósito ", true));

        assertThat(resp.estado()).isEqualTo(EstadoReclamo.RESUELTO);
        assertThat(resp.respuesta()).isEqualTo("Disculpa la demora, te descontamos el depósito");
        assertThat(resp.resueltoEn()).isNotNull();
        ArgumentCaptor<ReclamoRegistrado> evento = ArgumentCaptor.forClass(ReclamoRegistrado.class);
        verify(eventos).publishEvent(evento.capture());
        assertThat(evento.getValue().aviso()).isEqualTo(ReclamoRegistrado.Aviso.RESPONDIDO);
    }

    @Test
    @DisplayName("Responder sin resolver lo deja EN_PROCESO; uno resuelto ya no se responde (409)")
    void enProcesoYLuegoNoSeRepite() {
        Reclamo r = guardado(paciente, cita(), EstadoReclamo.ABIERTO);
        assertThat(servicio.responder(usuarioOdontologo.getId(), r.getId(), new RespuestaRequest("Lo reviso", false))
                .estado()).isEqualTo(EstadoReclamo.EN_PROCESO);

        r.setEstado(EstadoReclamo.RESUELTO);
        assertThatThrownBy(() -> servicio.responder(usuarioOdontologo.getId(), r.getId(), new RespuestaRequest("x", true)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
    }

    @Test
    @DisplayName("Otro odontólogo no puede responder ni ver el reclamo (404)")
    void otroOdontologoNo() {
        Reclamo r = guardado(paciente, cita(), EstadoReclamo.ABIERTO);
        UUID otro = UUID.randomUUID();
        assertThatThrownBy(() -> servicio.responder(otro, r.getId(), new RespuestaRequest("x", true)))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
        assertThatThrownBy(() -> servicio.detalle(otro, r.getId()))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(404));
        assertThat(servicio.detalle(usuarioOdontologo.getId(), r.getId()).asunto()).isEqualTo("Cobro");
    }

    @Test
    @DisplayName("El reclamante cierra su reclamo; cerrarlo dos veces da 409")
    void cerrar() {
        Reclamo r = guardado(paciente, null, EstadoReclamo.ABIERTO);
        ReclamoResponse resp = servicio.cerrar(paciente.getId(), r.getId());
        assertThat(resp.estado()).isEqualTo(EstadoReclamo.CERRADO);
        assertThat(resp.resueltoEn()).isNotNull();
        assertThatThrownBy(() -> servicio.cerrar(paciente.getId(), r.getId()))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));
    }

    @Test
    @DisplayName("La bandeja del odontólogo filtra por estado")
    void bandejaFiltra() {
        Cita c = cita();
        Reclamo abierto = guardado(paciente, c, EstadoReclamo.ABIERTO);
        Reclamo resuelto = guardado(paciente, c, EstadoReclamo.RESUELTO);
        when(reclamos.paraOdontologo(usuarioOdontologo.getId())).thenReturn(List.of(abierto, resuelto));

        assertThat(servicio.paraOdontologo(usuarioOdontologo.getId(), null)).hasSize(2);
        assertThat(servicio.paraOdontologo(usuarioOdontologo.getId(), EstadoReclamo.ABIERTO))
                .singleElement().satisfies(x -> assertThat(x.id()).isEqualTo(abierto.getId()));
    }

    @Test
    @DisplayName("Días hábiles: saltan sábados y domingos")
    void diasHabiles() {
        // viernes 16/10 + 1 hábil = lunes 19/10
        assertThat(ReclamoService.sumarDiasHabiles(LocalDate.of(2026, 10, 16), 1)).isEqualTo(LocalDate.of(2026, 10, 19));
        // lunes 12/10 + 15 hábiles = lunes 2/11
        assertThat(ReclamoService.sumarDiasHabiles(LocalDate.of(2026, 10, 12), 15)).isEqualTo(LocalDate.of(2026, 11, 2));
    }
}

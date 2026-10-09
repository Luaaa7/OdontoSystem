package com.odontosystem.OdontoSystem.chatbot;

import com.odontosystem.OdontoSystem.citas.CitaDtos;
import com.odontosystem.OdontoSystem.citas.CitaDtos.CitaResponse;
import com.odontosystem.OdontoSystem.citas.CitaDtos.DisponibilidadResponse;
import com.odontosystem.OdontoSystem.citas.CitaDtos.ReservaRequest;
import com.odontosystem.OdontoSystem.citas.CitaDtos.Turno;
import com.odontosystem.OdontoSystem.citas.CitaService;
import com.odontosystem.OdontoSystem.citas.DisponibilidadService;
import com.odontosystem.OdontoSystem.entity.CanalChat;
import com.odontosystem.OdontoSystem.entity.DiaSemana;
import com.odontosystem.OdontoSystem.entity.EstadoChat;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.MensajeChat;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.SesionChat;
import com.odontosystem.OdontoSystem.entity.TipoEmisorMensaje;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.odontologos.BusquedaOdontologosService;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.FiltroBusqueda;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.HorarioDto;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.OdontologoTarjeta;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.Pagina;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.PerfilPublicoResponse;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ServicioResponse;
import com.odontosystem.OdontoSystem.odontologos.PerfilOdontologoService;
import com.odontosystem.OdontoSystem.repository.MensajeChatRepository;
import com.odontosystem.OdontoSystem.repository.SesionChatRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas del chatbot: la base de datos y los demás servicios se simulan con Mockito,
 * así que corren en milisegundos y sin conexión a Supabase.
 * "Ahora" es el viernes 9/10/2026 a las 08:00 en Ica.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatbotServiceTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final ZoneOffset MENOS_5 = ZoneOffset.ofHours(-5);
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-09T13:00:00Z"), LIMA);
    private static final LocalDate HOY = LocalDate.of(2026, 10, 9);

    @Mock private SesionChatRepository sesionChatRepository;
    @Mock private MensajeChatRepository mensajeChatRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private BusquedaOdontologosService busqueda;
    @Mock private PerfilOdontologoService perfiles;
    @Mock private DisponibilidadService disponibilidad;
    @Mock private CitaService citas;
    @Mock private PlatformTransactionManager transacciones;

    private ChatbotService servicio;
    private Usuario paciente;
    private SesionChat sesion;
    private final Map<UUID, SesionChat> sesiones = new HashMap<>();

    // Datos de un odontólogo de Parcona con dos servicios y horario de lunes a viernes
    private final UUID anaId = UUID.randomUUID();
    private final UUID limpiezaId = UUID.randomUUID();
    private final UUID resinaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // El detector y el extractor son reales: así también se prueba que entienden los mensajes.
        servicio = new ChatbotService(new IntentDetector(), new DistritoExtractor(), sesionChatRepository,
                mensajeChatRepository, usuarioRepository, busqueda, perfiles, disponibilidad, citas, RELOJ, transacciones);
        paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Paciente Prueba").rol(RolUsuario.PACIENTE).build();
        sesion = sesionDe(paciente);

        when(sesionChatRepository.findById(any())).thenAnswer(i -> Optional.ofNullable(sesiones.get(i.<UUID>getArgument(0))));
        when(sesionChatRepository.findFirstByUsuarioIdAndEstadoOrderByActualizadoEnDesc(paciente.getId(), EstadoChat.ACTIVA))
                .thenReturn(Optional.of(sesion));
        when(sesionChatRepository.save(any(SesionChat.class))).thenAnswer(i -> {
            SesionChat s = i.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID()); // simula el id que asigna Hibernate
            sesiones.put(s.getId(), s);
            return s;
        });
        when(citas.misCitas(any())).thenReturn(List.of());
    }

    private SesionChat sesionDe(Usuario usuario) {
        SesionChat s = SesionChat.builder()
                .id(UUID.randomUUID())
                .usuario(usuario)
                .canal(CanalChat.WEB)
                .estado(EstadoChat.ACTIVA)
                .build();
        sesiones.put(s.getId(), s);
        return s;
    }

    private RespuestaChatbot escribir(String texto) {
        return servicio.procesarMensaje(paciente.getId(), new MensajeChatRequest(texto, null, null, null, null));
    }

    private RespuestaChatbot pulsar(String etiqueta, String accion, String valor) {
        return servicio.procesarMensaje(paciente.getId(), new MensajeChatRequest(etiqueta, null, null, accion, valor));
    }

    private static OdontologoTarjeta tarjeta(UUID id, String nombre, String distrito) {
        return new OdontologoTarjeta(id, nombre, null, "Consultorio " + nombre, distrito, new BigDecimal("4.50"), 10,
                List.of("Odontología General"), new BigDecimal("80.00"), null);
    }

    private void conOdontologos(OdontologoTarjeta... tarjetas) {
        when(busqueda.buscar(any())).thenReturn(new Pagina<>(List.of(tarjetas), 0, 50, tarjetas.length, 1));
    }

    private static ServicioResponse servicio(UUID id, String titulo, String precio, String deposito) {
        return new ServicioResponse(id, (short) 1, "General", titulo, null, new BigDecimal(precio), new BigDecimal(deposito),
                (short) 30, true);
    }

    /** Ana Torres (Parcona): limpieza y resina, de lunes a viernes; turnos libres a las 9, 10 y 11. */
    private void conAnaEnParcona() {
        conOdontologos(tarjeta(anaId, "Dra. Ana Torres", "Parcona"), tarjeta(UUID.randomUUID(), "Dr. Luis Pérez", "La Tinguiña"));
        List<HorarioDto> horario = List.of(DiaSemana.LUNES, DiaSemana.MARTES, DiaSemana.MIERCOLES, DiaSemana.JUEVES,
                DiaSemana.VIERNES).stream().map(d -> new HorarioDto(d, LocalTime.of(9, 0), LocalTime.of(12, 0), (short) 60)).toList();
        when(perfiles.perfilPublico(anaId)).thenReturn(new PerfilPublicoResponse(anaId, "Dra. Ana Torres", null,
                "Sonrisas Parcona", "Av. Principal 123", "Parcona", null, null, "COP-1", true, new BigDecimal("4.50"), 10,
                false, List.of(), List.of(servicio(limpiezaId, "Limpieza dental", "80", "16"),
                servicio(resinaId, "Resina estética", "150", "30")), horario, List.of()));
        when(disponibilidad.disponibilidad(eq(anaId), any(), any())).thenAnswer(i -> {
            UUID servicioId = i.getArgument(1);
            LocalDate fecha = i.getArgument(2);
            boolean finDeSemana = fecha.getDayOfWeek() == DayOfWeek.SATURDAY || fecha.getDayOfWeek() == DayOfWeek.SUNDAY;
            List<Turno> turnos = finDeSemana ? List.of() : List.of(9, 10, 11).stream()
                    .map(h -> OffsetDateTime.of(fecha, LocalTime.of(h, 0), MENOS_5))
                    .map(t -> new Turno(t, t.plusMinutes(30))).toList();
            return new DisponibilidadResponse(anaId, servicioId, fecha, "America/Lima", (short) 30, turnos);
        });
    }

    private ContextoChat contextoGuardado() {
        return ContextoChat.leer(sesion.getContexto());
    }

    private CitaResponse cita(EstadoCita estado, OffsetDateTime inicio, String odontologo) {
        return new CitaResponse(UUID.randomUUID(), estado, inicio, inicio.plusMinutes(30), "America/Lima",
                new BigDecimal("150"), new BigDecimal("30"), null,
                new CitaDtos.OdontologoResumen(anaId, odontologo, "Sonrisas Parcona", "Av. Principal 123", "Parcona"),
                new CitaDtos.PacienteResumen(paciente.getId(), paciente.getNombreCompleto(), null, null),
                new CitaDtos.ServicioResumen(resinaId, "Resina estética"), null, OffsetDateTime.now(RELOJ));
    }

    // ------------------------------------------------------------------ sesiones

    @Test
    @DisplayName("Sin conversación activa: crea una nueva y guarda los dos mensajes en orden")
    void creaSesionNuevaYGuardaMensajes() {
        when(sesionChatRepository.findFirstByUsuarioIdAndEstadoOrderByActualizadoEnDesc(paciente.getId(), EstadoChat.ACTIVA))
                .thenReturn(Optional.empty());
        when(usuarioRepository.getReferenceById(paciente.getId())).thenReturn(paciente);

        RespuestaChatbot respuesta = escribir("hola");

        assertThat(respuesta.sesionId()).isNotNull().isNotEqualTo(sesion.getId());
        assertThat(respuesta.intencion()).isEqualTo("SALUDO");
        ArgumentCaptor<MensajeChat> captor = ArgumentCaptor.forClass(MensajeChat.class);
        verify(mensajeChatRepository, times(2)).save(captor.capture());
        List<MensajeChat> guardados = captor.getAllValues();
        assertThat(guardados.get(0).getTipoEmisor()).isEqualTo(TipoEmisorMensaje.USUARIO);
        assertThat(guardados.get(0).getTextoMensaje()).isEqualTo("hola");
        assertThat(guardados.get(1).getTipoEmisor()).isEqualTo(TipoEmisorMensaje.BOT);
        assertThat(guardados.get(1).getTextoMensaje()).isEqualTo(respuesta.mensaje());
        assertThat(guardados.get(1).getCreadoEn()).isAfter(guardados.get(0).getCreadoEn());
    }

    @Test
    @DisplayName("Si ya hay una conversación activa, la reutiliza")
    void reutilizaSesionActiva() {
        conOdontologos();
        RespuestaChatbot respuesta = escribir("busco un dentista");

        assertThat(respuesta.sesionId()).isEqualTo(sesion.getId());
        verify(usuarioRepository, never()).getReferenceById(any());
    }

    @Test
    @DisplayName("No permite escribir en la conversación de otro usuario (403)")
    void rechazaSesionAjena() {
        SesionChat ajena = sesionDe(Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Otra Persona").build());

        assertThatThrownBy(() -> servicio.procesarMensaje(paciente.getId(),
                new MensajeChatRequest("hola", ajena.getId(), null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
        verify(mensajeChatRepository, never()).save(any());
    }

    @Test
    @DisplayName("Una conversación que no existe responde 404")
    void sesionInexistente() {
        assertThatThrownBy(() -> servicio.procesarMensaje(paciente.getId(),
                new MensajeChatRequest("hola", UUID.randomUUID(), null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("El historial devuelve los mensajes de la conversación en orden")
    void historialEnOrden() {
        OffsetDateTime t0 = OffsetDateTime.now(RELOJ);
        when(mensajeChatRepository.findBySesionIdOrderByCreadoEnAsc(sesion.getId())).thenReturn(List.of(
                MensajeChat.builder().sesion(sesion).tipoEmisor(TipoEmisorMensaje.USUARIO)
                        .textoMensaje("hola").intencionDetectada("SALUDO").creadoEn(t0).build(),
                MensajeChat.builder().sesion(sesion).tipoEmisor(TipoEmisorMensaje.BOT)
                        .textoMensaje("¡Hola, Paciente!").intencionDetectada("SALUDO").creadoEn(t0.plusSeconds(1)).build()));

        List<MensajeChatResponse> historial = servicio.obtenerHistorial(paciente.getId(), sesion.getId());

        assertThat(historial).extracting(MensajeChatResponse::emisor)
                .containsExactly(TipoEmisorMensaje.USUARIO, TipoEmisorMensaje.BOT);
    }

    // ------------------------------------------------------------------ saludo y menú

    @Test
    @DisplayName("Paciente nuevo: el saludo usa su primer nombre y le ofrece agendar la primera cita")
    void saludoPacienteNuevo() {
        RespuestaChatbot respuesta = escribir("Buenos días");

        assertThat(respuesta.mensaje()).startsWith("¡Hola, Paciente!").contains("primera cita");
        assertThat(respuesta.opciones()).extracting(Opcion::accion).contains("agendar_cita");
    }

    @Test
    @DisplayName("Con una cita próxima, el saludo se la recuerda")
    void saludoRecuerdaProximaCita() {
        when(citas.misCitas(paciente.getId())).thenReturn(List.of(
                cita(EstadoCita.CONFIRMADA, OffsetDateTime.of(2026, 10, 12, 10, 0, 0, 0, MENOS_5), "Dra. Ana Torres")));

        RespuestaChatbot respuesta = escribir("hola");

        assertThat(respuesta.mensaje()).contains("Recuerda que tienes una cita el lunes 12/10 a las 10:00 con Dra. Ana Torres");
    }

    @Test
    @DisplayName("Si su última visita fue hace más de 6 meses, el saludo sugiere agendar el control")
    void saludoSugiereControl() {
        when(citas.misCitas(paciente.getId())).thenReturn(List.of(
                cita(EstadoCita.COMPLETADA, OffsetDateTime.of(2026, 2, 2, 10, 0, 0, 0, MENOS_5), "Dra. Ana Torres")));

        RespuestaChatbot respuesta = escribir("hola");

        assertThat(respuesta.mensaje()).contains("hace 8 meses").contains("control");
    }

    @Test
    @DisplayName("Un mensaje no reconocido muestra el menú de ayuda")
    void desconocidaMuestraMenu() {
        RespuestaChatbot respuesta = escribir("asdfgh");

        assertThat(respuesta.intencion()).isEqualTo("DESCONOCIDA");
        assertThat(respuesta.acciones()).containsExactlyElementsOf(ChatbotService.ACCIONES_MENU);
        assertThat(respuesta.opciones()).hasSize(3);
    }

    // ------------------------------------------------------------------ búsqueda por distrito

    @Test
    @DisplayName("Si el mensaje trae el distrito, busca directo y devuelve solo los de ese distrito")
    void buscaConDistritoEnElMensaje() {
        conAnaEnParcona();

        RespuestaChatbot respuesta = escribir("busco un dentista en Parcona");

        assertThat(respuesta.tipo()).isEqualTo(RespuestaChatbot.LISTA_ODONTOLOGOS);
        assertThat(respuesta.mensaje()).startsWith("Encontré 1 odontólogo en Parcona:");
        @SuppressWarnings("unchecked")
        List<OdontologoResumen> datos = (List<OdontologoResumen>) respuesta.datos();
        assertThat(datos).extracting(OdontologoResumen::nombre).containsExactly("Dra. Ana Torres");
        assertThat(respuesta.opciones()).extracting(Opcion::valor).containsExactly(anaId.toString());
        assertThat(contextoGuardado().getPaso()).isEqualTo(ContextoChat.Paso.ELEGIR_ODONTOLOGO);
    }

    @Test
    @DisplayName("Sin odontólogos en el distrito, lo dice y ofrece los distritos que sí tienen")
    void sinResultadosEnElDistrito() {
        conAnaEnParcona();

        RespuestaChatbot respuesta = escribir("busco odontólogo en Subtanjalla");

        assertThat(respuesta.mensaje()).contains("Subtanjalla").contains("Parcona");
        assertThat(respuesta.opciones()).extracting(Opcion::valor).containsExactly("La Tinguiña", "Parcona");
    }

    @Test
    @DisplayName("Memoria de contexto: si el bot preguntó el distrito, \"Parcona\" se entiende como respuesta")
    void recuerdaQueElBotPreguntoElDistrito() {
        conAnaEnParcona();

        escribir("busco un dentista");
        assertThat(contextoGuardado().getPaso()).isEqualTo(ContextoChat.Paso.ELEGIR_DISTRITO);
        RespuestaChatbot respuesta = escribir("Parcona");

        assertThat(respuesta.tipo()).isEqualTo(RespuestaChatbot.LISTA_ODONTOLOGOS);
    }

    @Test
    @DisplayName("Sin contexto previo, un distrito suelto no se adivina: muestra el menú")
    void distritoSueltoSinContexto() {
        RespuestaChatbot respuesta = escribir("Parcona");

        assertThat(respuesta.intencion()).isEqualTo("DESCONOCIDA");
        verify(busqueda, never()).buscar(any());
    }

    // ------------------------------------------------------------------ agendar de punta a punta

    @Test
    @DisplayName("Agenda completa por texto: distrito → \"1\" → \"el segundo\" → \"a las 10\" → \"sí\"")
    void agendaCompletaPorTexto() {
        conAnaEnParcona();
        OffsetDateTime diezAm = OffsetDateTime.of(HOY, LocalTime.of(10, 0), MENOS_5);
        when(citas.reservar(eq(paciente.getId()), any())).thenReturn(cita(EstadoCita.PENDIENTE_PAGO, diezAm, "Dra. Ana Torres"));

        escribir("quiero un dentista en Parcona");

        RespuestaChatbot servicios = escribir("1");
        assertThat(servicios.tipo()).isEqualTo(RespuestaChatbot.LISTA_SERVICIOS);

        RespuestaChatbot turnos = escribir("el segundo");
        assertThat(turnos.tipo()).isEqualTo(RespuestaChatbot.LISTA_HORARIOS);
        assertThat(turnos.mensaje()).contains("Resina estética").contains("viernes 9/10");
        assertThat(((DisponibilidadResponse) turnos.datos()).fecha()).isEqualTo(HOY);

        RespuestaChatbot confirmacion = escribir("a las 10");
        assertThat(confirmacion.tipo()).isEqualTo(RespuestaChatbot.CONFIRMACION);
        assertThat(confirmacion.mensaje()).contains("10:00").contains("S/ 30.00");

        RespuestaChatbot reservada = escribir("sí");
        assertThat(reservada.tipo()).isEqualTo(RespuestaChatbot.CITA_RESERVADA);
        assertThat(reservada.opciones()).extracting(Opcion::accion).contains("pagar_deposito");

        ArgumentCaptor<ReservaRequest> pedido = ArgumentCaptor.forClass(ReservaRequest.class);
        verify(citas).reservar(eq(paciente.getId()), pedido.capture());
        assertThat(pedido.getValue().odontologoId()).isEqualTo(anaId);
        assertThat(pedido.getValue().servicioId()).isEqualTo(resinaId);
        assertThat(pedido.getValue().inicio()).isEqualTo(diezAm);
        assertThat(contextoGuardado().getPaso()).isNull(); // terminada la reserva, la memoria se limpia
    }

    @Test
    @DisplayName("Memoria de contexto: \"¿y el lunes?\" muestra otro día con el mismo odontólogo y servicio")
    void otroDiaConElMismoServicio() {
        conAnaEnParcona();
        escribir("dentista en Parcona");
        escribir("1");
        escribir("limpieza");

        RespuestaChatbot lunes = escribir("¿y el lunes?");

        assertThat(lunes.tipo()).isEqualTo(RespuestaChatbot.LISTA_HORARIOS);
        assertThat(lunes.mensaje()).contains("Limpieza dental").contains("lunes 12/10");
        verify(disponibilidad).disponibilidad(anaId, limpiezaId, LocalDate.of(2026, 10, 12));
    }

    @Test
    @DisplayName("Si el turno se ocupa justo antes de confirmar, avisa y vuelve a mostrar los libres")
    void turnoOcupadoAlConfirmar() {
        conAnaEnParcona();
        when(citas.reservar(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Ese horario acaba de ser reservado; elige otro"));
        escribir("dentista en Parcona");
        escribir("1");
        escribir("1");
        escribir("9");

        RespuestaChatbot respuesta = escribir("confirmo");

        assertThat(respuesta.tipo()).isEqualTo(RespuestaChatbot.LISTA_HORARIOS);
        assertThat(respuesta.mensaje()).startsWith("Ese horario se acaba de ocupar");
        assertThat(contextoGuardado().getPaso()).isEqualTo(ContextoChat.Paso.ELEGIR_TURNO);
    }

    @Test
    @DisplayName("\"Cancelar\" en medio del agendamiento borra la memoria y vuelve al menú")
    void cancelarEnMedioDelFlujo() {
        conAnaEnParcona();
        escribir("dentista en Parcona");
        escribir("1");

        RespuestaChatbot respuesta = escribir("cancelar");

        assertThat(respuesta.mensaje()).startsWith("Listo, lo dejamos ahí");
        assertThat(contextoGuardado().getPaso()).isNull();
        verify(citas, never()).reservar(any(), any());
    }

    @Test
    @DisplayName("Botones: elegir_odontologo con su id funciona aunque no haya contexto previo")
    void botonElegirOdontologo() {
        conAnaEnParcona();

        RespuestaChatbot respuesta = pulsar("Dra. Ana Torres", "elegir_odontologo", anaId.toString());

        assertThat(respuesta.tipo()).isEqualTo(RespuestaChatbot.LISTA_SERVICIOS);
        assertThat(respuesta.opciones()).extracting(Opcion::accion).containsOnly("elegir_servicio");
    }

    @Test
    @DisplayName("Un botón con un valor inválido no rompe el chat")
    void botonConValorInvalido() {
        RespuestaChatbot respuesta = pulsar("???", "elegir_odontologo", "no-es-un-uuid");

        assertThat(respuesta.mensaje()).startsWith("No pude leer esa opción");
    }

    @Test
    @DisplayName("\"Agendar con el Dr. Pérez\" busca por nombre y va directo a sus servicios")
    void agendarConNombre() {
        conAnaEnParcona();
        when(busqueda.buscar(any())).thenAnswer(i -> {
            FiltroBusqueda f = i.getArgument(0);
            List<OdontologoTarjeta> r = "torres".equals(f.q()) ? List.of(tarjeta(anaId, "Dra. Ana Torres", "Parcona")) : List.of();
            return new Pagina<>(r, 0, 50, r.size(), 1);
        });

        RespuestaChatbot respuesta = escribir("quiero agendar con la Dra. Torres");

        assertThat(respuesta.tipo()).isEqualTo(RespuestaChatbot.LISTA_SERVICIOS);
        assertThat(respuesta.mensaje()).startsWith("Dra. Ana Torres ofrece");
    }

    @Test
    @DisplayName("Un odontólogo no puede agendar por el chat")
    void odontologoNoAgenda() {
        paciente.setRol(RolUsuario.ODONTOLOGO);
        conAnaEnParcona();

        RespuestaChatbot respuesta = pulsar("Dra. Ana Torres", "elegir_odontologo", anaId.toString());

        assertThat(respuesta.mensaje()).contains("para pacientes");
        verify(perfiles, never()).perfilPublico(any());
    }

    // ------------------------------------------------------------------ mis citas

    @Test
    @DisplayName("\"Mis citas\" muestra las próximas con su estado y la última visita")
    void misCitasReales() {
        when(citas.misCitas(paciente.getId())).thenReturn(List.of(
                cita(EstadoCita.PENDIENTE_PAGO, OffsetDateTime.of(2026, 10, 12, 10, 0, 0, 0, MENOS_5), "Dra. Ana Torres"),
                cita(EstadoCita.COMPLETADA, OffsetDateTime.of(2026, 8, 3, 9, 0, 0, 0, MENOS_5), "Dr. Luis Pérez")));

        RespuestaChatbot respuesta = escribir("mis citas");

        assertThat(respuesta.tipo()).isEqualTo(RespuestaChatbot.LISTA_CITAS);
        assertThat(respuesta.mensaje())
                .contains("Tu próxima cita")
                .contains("lunes 12/10 a las 10:00 con Dra. Ana Torres")
                .contains("falta pagar el depósito")
                .contains("Tu última visita fue el lunes 3/08 con Dr. Luis Pérez");
        assertThat(respuesta.opciones()).extracting(Opcion::accion).contains("pagar_deposito");
    }

    @Test
    @DisplayName("Sin citas, \"mis citas\" ofrece agendar la primera")
    void sinCitas() {
        RespuestaChatbot respuesta = escribir("¿cuándo es mi próxima cita?");

        assertThat(respuesta.intencion()).isEqualTo("CONSULTAR_HISTORIAL");
        assertThat(respuesta.mensaje()).contains("Todavía no tienes citas");
    }
}

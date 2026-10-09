package com.odontosystem.OdontoSystem.chatbot;

import com.odontosystem.OdontoSystem.chatbot.ContextoChat.Paso;
import com.odontosystem.OdontoSystem.citas.CitaDtos.CitaResponse;
import com.odontosystem.OdontoSystem.citas.CitaDtos.DisponibilidadResponse;
import com.odontosystem.OdontoSystem.citas.CitaDtos.ReservaRequest;
import com.odontosystem.OdontoSystem.citas.CitaDtos.Turno;
import com.odontosystem.OdontoSystem.citas.CitaService;
import com.odontosystem.OdontoSystem.citas.DisponibilidadService;
import com.odontosystem.OdontoSystem.entity.CanalChat;
import com.odontosystem.OdontoSystem.entity.EstadoChat;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.MensajeChat;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.SesionChat;
import com.odontosystem.OdontoSystem.entity.TipoEmisorMensaje;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.odontologos.BusquedaOdontologosService;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.FiltroBusqueda;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.OdontologoTarjeta;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.PerfilPublicoResponse;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ServicioResponse;
import com.odontosystem.OdontoSystem.odontologos.PerfilOdontologoService;
import com.odontosystem.OdontoSystem.repository.MensajeChatRepository;
import com.odontosystem.OdontoSystem.repository.SesionChatRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orquesta una vuelta de conversación del chatbot (motor de reglas, sin IA):
 * <pre>
 * mensaje ─► ¿botón? ─► ¿"cancelar"? ─► ¿respuesta corta al paso en curso? ─► intención nueva
 * </pre>
 *
 * <b>Memoria de contexto.</b> En {@link ContextoChat} (sesiones_chat.contexto) se guarda el paso del
 * agendamiento y lo que el usuario eligió. Así "Parcona", "1", "a las 10", "¿y el lunes?" o "sí" se
 * entienden según lo que el bot acaba de preguntar, aunque lleguen en otro mensaje.
 *
 * <b>Agendar por chat:</b> distrito → odontólogo → servicio → turnos del primer día libre →
 * "¿confirmas?" → {@link CitaService#reservar} (queda PENDIENTE_PAGO y el frontend lleva a pagar).
 *
 * <b>Transacciones.</b> Leer la sesión y guardar los mensajes van en transacciones cortas propias.
 * Las consultas de negocio (buscador, disponibilidad, reservar) corren en las suyas: si una falla
 * (por ejemplo, "ese horario se acaba de ocupar"), el bot responde igual y la conversación se guarda.
 */
@Service
public class ChatbotService {

    static final List<String> ACCIONES_MENU = List.of("buscar_odontologo", "agendar_cita", "ver_historial");
    static final int MAX_RESULTADOS = 5;
    /** Cuántos días hacia adelante se buscan turnos al elegir un servicio. */
    static final int DIAS_A_BUSCAR = 14;
    /** Cuántas fechas alternativas se ofrecen como botones. */
    static final int FECHAS_ALTERNATIVAS = 4;
    static final ZoneId ZONA = ZoneId.of("America/Lima");
    private static final int MESES_PARA_CONTROL = 6;

    private static final Locale ES = Locale.of("es", "PE");
    private static final DateTimeFormatter DIA_LARGO = DateTimeFormatter.ofPattern("EEEE d/MM", ES);
    private static final DateTimeFormatter DIA_CORTO = DateTimeFormatter.ofPattern("EEE d/MM", ES);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    /** "agendar con el Dr. Pérez" → "perez" (el texto ya viene normalizado: sin tildes ni puntos). */
    private static final Pattern NOMBRE_DOCTOR = Pattern.compile(
            "\\bcon (?:el |la )?(?:dr |dra |doctor |doctora |odontologo |odontologa )?([a-zñ ]{3,60})");
    /** En una búsqueda ("quiero una cita con la doctora Ana") solo cuenta si lleva el título. */
    private static final Pattern NOMBRE_CON_TITULO = Pattern.compile(
            "\\bcon (?:el |la )?(?:dr|dra|doctor|doctora) ([a-zñ ]{3,60})");
    /** Lo que suele venir después del nombre: "con el dr perez para el lunes". */
    private static final Pattern FIN_DEL_NOMBRE = Pattern.compile(
            " (?:para|el|la|a|en|hoy|manana|este|esta|proximo|proxima|por|que|y)(?: |$)");
    private static final Set<EstadoCita> ESTADOS_PROXIMOS =
            Set.of(EstadoCita.PENDIENTE_PAGO, EstadoCita.PENDIENTE_CONFIRMACION, EstadoCita.CONFIRMADA);

    private final IntentDetector intentDetector;
    private final DistritoExtractor distritoExtractor;
    private final SesionChatRepository sesionChatRepository;
    private final MensajeChatRepository mensajeChatRepository;
    private final UsuarioRepository usuarioRepository;
    private final BusquedaOdontologosService busquedaService;
    private final PerfilOdontologoService perfilService;
    private final DisponibilidadService disponibilidadService;
    private final CitaService citaService;
    private final Clock clock;
    private final TransactionTemplate transaccion;

    public ChatbotService(IntentDetector intentDetector, DistritoExtractor distritoExtractor,
                          SesionChatRepository sesionChatRepository, MensajeChatRepository mensajeChatRepository,
                          UsuarioRepository usuarioRepository, BusquedaOdontologosService busquedaService,
                          PerfilOdontologoService perfilService, DisponibilidadService disponibilidadService,
                          CitaService citaService, Clock clock, PlatformTransactionManager transactionManager) {
        this.intentDetector = intentDetector;
        this.distritoExtractor = distritoExtractor;
        this.sesionChatRepository = sesionChatRepository;
        this.mensajeChatRepository = mensajeChatRepository;
        this.usuarioRepository = usuarioRepository;
        this.busquedaService = busquedaService;
        this.perfilService = perfilService;
        this.disponibilidadService = disponibilidadService;
        this.citaService = citaService;
        this.clock = clock;
        this.transaccion = new TransactionTemplate(transactionManager);
    }

    /** Lo que se sabe de la conversación al empezar la vuelta. */
    private record Conversacion(UUID sesionId, UUID usuarioId, String nombre, RolUsuario rol, ContextoChat contexto) {}

    /** El resultado de una vuelta: qué se respondió y cómo queda la memoria. */
    private record Vuelta(Intencion intencion, RespuestaChatbot respuesta, ContextoChat contexto) {}

    // ==================================================================
    // Entrada
    // ==================================================================

    public RespuestaChatbot procesarMensaje(UUID usuarioId, MensajeChatRequest request) {
        Conversacion conversacion = transaccion.execute(estado -> abrir(usuarioId, request));
        Vuelta vuelta = responder(Objects.requireNonNull(conversacion), request);
        transaccion.executeWithoutResult(estado -> guardar(conversacion, request.mensaje(), vuelta));
        return vuelta.respuesta();
    }

    @Transactional(readOnly = true)
    public List<MensajeChatResponse> obtenerHistorial(UUID usuarioId, UUID sesionId) {
        buscarSesionPropia(usuarioId, sesionId);
        return mensajeChatRepository.findBySesionIdOrderByCreadoEnAsc(sesionId).stream()
                .map(MensajeChatResponse::desde)
                .toList();
    }

    private Vuelta responder(Conversacion c, MensajeChatRequest request) {
        if (request.accion() != null && !request.accion().isBlank()) {
            return porAccion(c, request.accion().trim(), request.valor());
        }
        String texto = request.mensaje();
        String normalizado = IntentDetector.normalizar(texto);
        Paso paso = c.contexto().getPaso();

        if (paso != null && LectorRespuestas.esCancelacion(normalizado)) {
            return cancelarFlujo(c);
        }
        Intencion intencion = intentDetector.detectar(texto);
        Optional<String> distrito = distritoExtractor.extraer(texto);

        if (paso != null) {
            Optional<Vuelta> enContexto = continuarFlujo(c, texto, normalizado, intencion, distrito);
            if (enContexto.isPresent()) {
                return enContexto.get();
            }
        }
        return switch (intencion) {
            case SALUDO -> saludar(c);
            case CONSULTAR_HISTORIAL -> historial(c);
            case BUSCAR_ODONTOLOGO, AGENDAR_CITA -> iniciarBusqueda(c, intencion, distrito, normalizado);
            case DESCONOCIDA -> menu(c, "No entendí bien eso. Puedo ayudarte a:\n"
                    + "🔍 Buscar un odontólogo\n📅 Agendar una cita\n🕐 Ver tus citas");
        };
    }

    // ==================================================================
    // Botones (accion + valor)
    // ==================================================================

    private Vuelta porAccion(Conversacion c, String accion, String valor) {
        ContextoChat ctx = c.contexto();
        try {
            return switch (accion) {
                case "buscar_odontologo", "elegir_distrito" -> valor != null && distritoExtractor.extraer(valor).isPresent()
                        ? buscarEnDistrito(c, Intencion.BUSCAR_ODONTOLOGO, distritoExtractor.extraer(valor).get())
                        : preguntarDistrito(c, Intencion.BUSCAR_ODONTOLOGO);
                case "agendar_cita" -> preguntarDistrito(c, Intencion.AGENDAR_CITA);
                case "ver_historial" -> historial(c);
                case "elegir_odontologo", "ver_disponibilidad" -> elegirOdontologo(c, ctx, UUID.fromString(valor));
                case "elegir_servicio" -> ctx.getOdontologoId() == null
                        ? preguntarDistrito(c, Intencion.AGENDAR_CITA)
                        : elegirServicio(c, ctx, UUID.fromString(valor), null);
                case "elegir_fecha" -> ctx.getServicioId() == null
                        ? preguntarDistrito(c, Intencion.AGENDAR_CITA)
                        : mostrarTurnos(c, ctx, LocalDate.parse(valor), null);
                case "elegir_turno" -> elegirTurno(c, ctx, OffsetDateTime.parse(valor));
                case "confirmar" -> ctx.getPaso() == Paso.CONFIRMAR
                        ? reservar(c, ctx)
                        : menu(c, "No hay ninguna reserva por confirmar. ¿Qué quieres hacer?");
                case "cancelar" -> cancelarFlujo(c);
                default -> menu(c, "No reconozco esa opción. ¿Qué quieres hacer?");
            };
        } catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            // valor ausente o mal formado (un uuid o una fecha inválidos)
            return menu(c, "No pude leer esa opción. ¿Qué quieres hacer?");
        }
    }

    // ==================================================================
    // Respuestas cortas dentro de un flujo
    // ==================================================================

    private Optional<Vuelta> continuarFlujo(Conversacion c, String texto, String normalizado,
                                            Intencion intencion, Optional<String> distrito) {
        ContextoChat ctx = c.contexto();
        Paso paso = ctx.getPaso();
        // Preguntas nuevas que no son respuesta al paso en curso
        if (intencion == Intencion.SALUDO || intencion == Intencion.CONSULTAR_HISTORIAL) {
            return Optional.empty();
        }
        if (paso == Paso.ELEGIR_DISTRITO) {
            return distrito.map(d -> buscarEnDistrito(c, Intencion.BUSCAR_ODONTOLOGO, d));
        }
        if (paso == Paso.ELEGIR_SERVICIO) {
            // Va antes de mirar la intención: "limpieza" también es una palabra de búsqueda
            Optional<Vuelta> servicio = elegirServicioPorTexto(c, ctx, normalizado);
            if (servicio.isPresent()) {
                return servicio;
            }
        }
        if (intencion == Intencion.BUSCAR_ODONTOLOGO) {
            return Optional.empty(); // "busco otro dentista": empieza una búsqueda nueva
        }
        if (distrito.isPresent() && paso != Paso.CONFIRMAR) {
            // "mejor en Parcona": otra búsqueda, sin perder tiempo repreguntando
            return Optional.of(buscarEnDistrito(c, Intencion.BUSCAR_ODONTOLOGO, distrito.get()));
        }
        LocalDate hoy = hoy();
        return switch (paso) {
            case ELEGIR_ODONTOLOGO -> LectorRespuestas.indice(normalizado, ctx.getOdontologos().size())
                    .map(i -> elegirOdontologo(c, ctx, UUID.fromString(ctx.getOdontologos().get(i))))
                    .or(() -> Optional.of(repreguntar(c, ctx, "Elige un odontólogo con su número (1, 2…) "
                            + "o escribe \"cancelar\" para volver al menú.")));
            case ELEGIR_SERVICIO -> Optional.of(repreguntar(c, ctx, "Elige un servicio con su número (1, 2…) "
                    + "o escribe \"cancelar\"."));
            case ELEGIR_TURNO, CONFIRMAR -> {
                if (paso == Paso.CONFIRMAR && LectorRespuestas.esAfirmacion(normalizado)) {
                    yield Optional.of(reservar(c, ctx));
                }
                if (paso == Paso.CONFIRMAR && LectorRespuestas.esNegacion(normalizado)) {
                    yield Optional.of(mostrarTurnos(c, ctx, LocalDate.parse(ctx.getFecha()),
                            "Está bien, no reservé nada. Elige otro horario:"));
                }
                Optional<LocalDate> otraFecha = LectorRespuestas.fecha(texto, hoy);
                Optional<LocalTime> hora = LectorRespuestas.hora(texto);
                if (otraFecha.isPresent() && !otraFecha.get().toString().equals(ctx.getFecha())) {
                    // "¿y el lunes?": mismos odontólogo y servicio, otro día
                    Vuelta turnos = mostrarTurnos(c, ctx, otraFecha.get(), null);
                    if (hora.isPresent()) {
                        Optional<OffsetDateTime> elegido = turnoALaHora(turnos.contexto(), hora.get());
                        if (elegido.isPresent()) yield Optional.of(elegirTurno(c, turnos.contexto(), elegido.get()));
                    }
                    yield Optional.of(turnos);
                }
                Optional<OffsetDateTime> elegido = hora.flatMap(h -> turnoALaHora(ctx, h))
                        .or(() -> turnoPorNumero(ctx, normalizado));
                if (elegido.isPresent()) {
                    yield Optional.of(elegirTurno(c, ctx, elegido.get()));
                }
                if (hora.isPresent()) {
                    yield Optional.of(repreguntar(c, ctx, "A las " + HORA.format(hora.get())
                            + " no hay turno libre ese día. Elige uno de la lista o pide otro día (por ejemplo, \"¿y el lunes?\")."));
                }
                yield Optional.of(repreguntar(c, ctx, paso == Paso.CONFIRMAR
                        ? "¿Confirmas la reserva? Responde \"sí\" para reservar, \"no\" para elegir otro horario o \"cancelar\"."
                        : "Elige un horario (por ejemplo, \"a las 10\" o \"el 2\"), pide otro día o escribe \"cancelar\"."));
            }
            case ELEGIR_DISTRITO -> Optional.empty(); // ya atendido arriba
        };
    }

    /**
     * Número en la lista de turnos. "10" a secas se lee como hora si hay un turno a las 10:00
     * (horario de consultorio, 7 a 21); "el 2" u "opción 2" es siempre la posición en la lista.
     */
    private Optional<OffsetDateTime> turnoPorNumero(ContextoChat ctx, String normalizado) {
        Optional<Integer> numero = LectorRespuestas.numeroSuelto(normalizado);
        if (numero.isEmpty()) {
            return Optional.empty();
        }
        int n = numero.get();
        if (normalizado.matches("\\d{1,2}") && n >= 7 && n <= 21) {
            Optional<OffsetDateTime> porHora = turnoALaHora(ctx, LocalTime.of(n, 0));
            if (porHora.isPresent()) {
                return porHora;
            }
        }
        return LectorRespuestas.indice(normalizado, ctx.getTurnos().size())
                .map(i -> OffsetDateTime.parse(ctx.getTurnos().get(i)));
    }

    private static Optional<OffsetDateTime> turnoALaHora(ContextoChat ctx, LocalTime hora) {
        return ctx.getTurnos().stream()
                .map(OffsetDateTime::parse)
                .filter(t -> t.atZoneSameInstant(ZONA).toLocalTime().equals(hora))
                .findFirst();
    }

    private Optional<Vuelta> elegirServicioPorTexto(Conversacion c, ContextoChat ctx, String normalizado) {
        Optional<Integer> indice = LectorRespuestas.indice(normalizado, ctx.getServicios().size());
        if (indice.isPresent()) {
            return Optional.of(elegirServicio(c, ctx, UUID.fromString(ctx.getServicios().get(indice.get())), null));
        }
        // "la limpieza", "resina": se busca por el nombre del servicio
        PerfilPublicoResponse perfil;
        try {
            perfil = perfilService.perfilPublico(UUID.fromString(ctx.getOdontologoId()));
        } catch (ResponseStatusException e) {
            return Optional.of(menu(c, "Ese odontólogo ya no está disponible. ¿Buscamos otro?"));
        }
        List<String> palabras = List.of(normalizado.split(" ")).stream().filter(p -> p.length() >= 4).toList();
        return perfil.servicios().stream()
                .filter(s -> palabras.stream().anyMatch(p -> IntentDetector.normalizar(s.titulo()).contains(p)))
                .findFirst()
                .map(s -> elegirServicio(c, ctx, s.id(), s));
    }

    // ==================================================================
    // Pasos del agendamiento
    // ==================================================================

    private Vuelta iniciarBusqueda(Conversacion c, Intencion intencion, Optional<String> distrito, String normalizado) {
        if (distrito.isPresent()) {
            return buscarEnDistrito(c, intencion, distrito.get());
        }
        Matcher m = (intencion == Intencion.AGENDAR_CITA ? NOMBRE_DOCTOR : NOMBRE_CON_TITULO).matcher(normalizado);
        if (m.find()) {
            String nombre = FIN_DEL_NOMBRE.split(m.group(1).trim(), 2)[0].trim();
            if (nombre.length() >= 3) {
                return buscarPorNombre(c, nombre);
            }
        }
        return preguntarDistrito(c, intencion);
    }

    private Vuelta preguntarDistrito(Conversacion c, Intencion intencion) {
        return preguntarDistrito(c, intencion, null);
    }

    /** @param encabezado lo que se dice antes de la lista de distritos (null = la pregunta de siempre) */
    private Vuelta preguntarDistrito(Conversacion c, Intencion intencion, String encabezado) {
        ContextoChat ctx = new ContextoChat();
        ctx.setPaso(Paso.ELEGIR_DISTRITO);
        List<String> distritos = todosLosOdontologos().stream()
                .map(OdontologoTarjeta::distrito)
                .filter(Objects::nonNull)
                .map(d -> distritoExtractor.extraer(d).orElse(d))
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream().sorted().toList();
        String mensaje = encabezado != null ? encabezado : intencion == Intencion.AGENDAR_CITA
                ? "¡Vamos a agendar! Primero elige un odontólogo: ¿en qué distrito de Ica lo buscamos?"
                : "¿En qué distrito de Ica te gustaría atenderte?";
        if (!distritos.isEmpty()) {
            mensaje += " Hay odontólogos en: " + String.join(", ", distritos) + ".";
        }
        List<Opcion> opciones = distritos.stream().map(d -> new Opcion(d, "elegir_distrito", d)).toList();
        return new Vuelta(intencion, respuesta(c, RespuestaChatbot.TEXTO_SIMPLE, mensaje, intencion, null,
                List.of("elegir_distrito"), opciones), ctx);
    }

    private Vuelta buscarEnDistrito(Conversacion c, Intencion intencion, String distrito) {
        List<OdontologoTarjeta> encontrados = todosLosOdontologos().stream()
                .filter(t -> distritoExtractor.coincide(t.distrito(), distrito))
                .limit(MAX_RESULTADOS)
                .toList();
        if (encontrados.isEmpty()) {
            return preguntarDistrito(c, intencion,
                    "Todavía no tengo odontólogos verificados en " + distrito + ". ¿Quieres buscar en otro distrito?");
        }
        return listarOdontologos(c, intencion, encontrados,
                encontrados.size() == 1 ? "Encontré 1 odontólogo en " + distrito + ":"
                        : "Encontré " + encontrados.size() + " odontólogos en " + distrito + ":", distrito);
    }

    private Vuelta buscarPorNombre(Conversacion c, String nombre) {
        List<OdontologoTarjeta> encontrados = busquedaService.buscar(
                new FiltroBusqueda(null, null, null, nombre, null, null, null, "calificacion", 0, 50)).contenido();
        if (encontrados.size() == 1) {
            return elegirOdontologo(c, new ContextoChat(), encontrados.get(0).id());
        }
        if (encontrados.isEmpty()) {
            return preguntarDistrito(c, Intencion.AGENDAR_CITA,
                    "No encontré un odontólogo llamado \"" + nombre + "\". ¿En qué distrito lo buscamos?");
        }
        return listarOdontologos(c, Intencion.AGENDAR_CITA, encontrados.stream().limit(MAX_RESULTADOS).toList(),
                "Encontré varios odontólogos con \"" + nombre + "\". ¿Cuál es?", null);
    }

    private Vuelta listarOdontologos(Conversacion c, Intencion intencion, List<OdontologoTarjeta> lista,
                                     String mensaje, String distrito) {
        ContextoChat ctx = new ContextoChat();
        ctx.setPaso(Paso.ELEGIR_ODONTOLOGO);
        ctx.setDistrito(distrito);
        ctx.setOdontologos(lista.stream().map(t -> t.id().toString()).toList());
        List<Opcion> opciones = lista.stream()
                .map(t -> new Opcion(t.nombre(), "elegir_odontologo", t.id().toString())).toList();
        return new Vuelta(intencion, respuesta(c, RespuestaChatbot.LISTA_ODONTOLOGOS,
                mensaje + " Elige uno para ver sus horarios.", intencion,
                lista.stream().map(OdontologoResumen::desde).toList(), List.of("ver_disponibilidad"), opciones), ctx);
    }

    private Vuelta elegirOdontologo(Conversacion c, ContextoChat anterior, UUID odontologoId) {
        if (c.rol() == RolUsuario.ODONTOLOGO) {
            return soloPacientes(c);
        }
        PerfilPublicoResponse perfil;
        try {
            perfil = perfilService.perfilPublico(odontologoId);
        } catch (ResponseStatusException e) {
            return menu(c, "Ese odontólogo ya no está disponible. ¿Buscamos otro?");
        }
        List<ServicioResponse> servicios = perfil.servicios();
        if (servicios.isEmpty()) {
            return repreguntar(c, anterior, perfil.nombre() + " todavía no tiene servicios para reservar. Elige otro odontólogo.");
        }
        ContextoChat ctx = new ContextoChat();
        ctx.setDistrito(anterior.getDistrito());
        ctx.setOdontologos(anterior.getOdontologos());
        ctx.setOdontologoId(perfil.id().toString());
        ctx.setOdontologoNombre(perfil.nombre());
        ctx.setDias(perfil.horarios().stream().map(h -> h.diaSemana().name()).distinct().toList());
        ctx.setServicios(servicios.stream().map(s -> s.id().toString()).toList());

        if (servicios.size() == 1) {
            return elegirServicio(c, ctx, servicios.get(0).id(), servicios.get(0));
        }
        ctx.setPaso(Paso.ELEGIR_SERVICIO);
        List<Opcion> opciones = servicios.stream()
                .map(s -> new Opcion(s.titulo() + " · " + soles(s.precioTotal()), "elegir_servicio", s.id().toString()))
                .toList();
        return new Vuelta(Intencion.AGENDAR_CITA, respuesta(c, RespuestaChatbot.LISTA_SERVICIOS,
                perfil.nombre() + " ofrece estos servicios. ¿Cuál necesitas?", Intencion.AGENDAR_CITA,
                servicios, List.of("elegir_servicio"), opciones), ctx);
    }

    /** Elige el servicio y muestra los turnos del primer día con horario libre. */
    private Vuelta elegirServicio(Conversacion c, ContextoChat ctx, UUID servicioId, ServicioResponse yaCargado) {
        ServicioResponse servicio = yaCargado;
        if (servicio == null) {
            try {
                servicio = perfilService.perfilPublico(UUID.fromString(ctx.getOdontologoId())).servicios().stream()
                        .filter(s -> s.id().equals(servicioId)).findFirst().orElse(null);
            } catch (ResponseStatusException e) {
                return menu(c, "Ese odontólogo ya no está disponible. ¿Buscamos otro?");
            }
            if (servicio == null) {
                return repreguntar(c, ctx, "Ese servicio ya no está disponible. Elige otro de la lista.");
            }
        }
        ctx.setServicioId(servicio.id().toString());
        ctx.setServicioNombre(servicio.titulo());
        ctx.setPrecio(servicio.precioTotal() == null ? null : servicio.precioTotal().toPlainString());
        ctx.setDeposito(servicio.montoDeposito() == null ? null : servicio.montoDeposito().toPlainString());

        for (LocalDate fecha : fechasConHorario(ctx, hoy(), DIAS_A_BUSCAR)) {
            Optional<DisponibilidadResponse> disp = disponibilidad(ctx, fecha);
            if (disp.isPresent() && !disp.get().turnos().isEmpty()) {
                return armarTurnos(c, ctx, fecha, null, disp.get());
            }
        }
        ctx.setPaso(ctx.getOdontologos().isEmpty() ? null : Paso.ELEGIR_ODONTOLOGO);
        return new Vuelta(Intencion.AGENDAR_CITA, respuesta(c, RespuestaChatbot.TEXTO_SIMPLE,
                ctx.getOdontologoNombre() + " no tiene turnos libres para " + servicio.titulo()
                        + " en las próximas 2 semanas. ¿Probamos con otro odontólogo?",
                Intencion.AGENDAR_CITA, null, ACCIONES_MENU, List.of(Opcion.de("Buscar otro odontólogo", "buscar_odontologo"))), ctx);
    }

    private Vuelta mostrarTurnos(Conversacion c, ContextoChat ctx, LocalDate fecha, String mensajePrevio) {
        LocalDate hoy = hoy();
        if (fecha.isBefore(hoy)) {
            return repreguntar(c, ctx, "Esa fecha ya pasó. Elige un día desde hoy.");
        }
        if (fecha.isAfter(hoy.plusDays(60))) {
            return repreguntar(c, ctx, "Solo se puede reservar hasta 60 días adelante. Elige una fecha más cercana.");
        }
        Optional<DisponibilidadResponse> disp = disponibilidad(ctx, fecha);
        if (disp.isEmpty()) {
            return menu(c, "Ese servicio ya no está disponible. ¿Buscamos otro odontólogo?");
        }
        return armarTurnos(c, ctx, fecha, mensajePrevio, disp.get());
    }

    private Vuelta armarTurnos(Conversacion c, ContextoChat ctx, LocalDate fecha, String mensajePrevio,
                               DisponibilidadResponse disponibilidad) {
        LocalDate hoy = hoy();
        List<Turno> turnos = disponibilidad.turnos();
        ctx.setPaso(Paso.ELEGIR_TURNO);
        ctx.setFecha(fecha.toString());
        ctx.setTurno(null);
        ctx.setTurnos(turnos.stream().map(t -> t.inicio().toString()).toList());

        List<Opcion> otrasFechas = fechasConHorario(ctx, hoy, DIAS_A_BUSCAR).stream()
                .filter(f -> !f.equals(fecha))
                .limit(FECHAS_ALTERNATIVAS)
                .map(f -> new Opcion("Otro día: " + DIA_CORTO.format(f), "elegir_fecha", f.toString()))
                .toList();

        String dia = DIA_LARGO.format(fecha);
        if (turnos.isEmpty()) {
            String texto = (mensajePrevio == null ? "" : mensajePrevio + " ")
                    + "El " + dia + " no hay turnos libres con " + ctx.getOdontologoNombre() + ". Prueba otro día:";
            return new Vuelta(Intencion.AGENDAR_CITA, respuesta(c, RespuestaChatbot.TEXTO_SIMPLE, texto,
                    Intencion.AGENDAR_CITA, null, List.of("elegir_fecha"), otrasFechas), ctx);
        }
        List<Opcion> opciones = new ArrayList<>();
        turnos.forEach(t -> opciones.add(new Opcion(hora(t.inicio()), "elegir_turno", t.inicio().toString())));
        opciones.addAll(otrasFechas);
        String texto = (mensajePrevio == null ? "" : mensajePrevio + " ")
                + "Turnos libres con " + ctx.getOdontologoNombre() + " para " + ctx.getServicioNombre()
                + " el " + dia + ". ¿Cuál prefieres?";
        return new Vuelta(Intencion.AGENDAR_CITA, respuesta(c, RespuestaChatbot.LISTA_HORARIOS, texto,
                Intencion.AGENDAR_CITA, disponibilidad, List.of("elegir_turno", "elegir_fecha"), opciones), ctx);
    }

    private Vuelta elegirTurno(Conversacion c, ContextoChat ctx, OffsetDateTime inicio) {
        boolean seMostro = ctx.getTurnos().stream().map(OffsetDateTime::parse).anyMatch(t -> t.isEqual(inicio));
        if (!seMostro || ctx.getServicioId() == null) {
            return repreguntar(c, ctx, "Ese horario no está en la lista. Elige uno de los turnos libres.");
        }
        ctx.setPaso(Paso.CONFIRMAR);
        ctx.setTurno(inicio.toString());
        String cuando = "el " + DIA_LARGO.format(inicio.atZoneSameInstant(ZONA)) + " a las " + hora(inicio);
        String texto = "Vas a reservar " + ctx.getServicioNombre() + " con " + ctx.getOdontologoNombre() + " " + cuando + ".";
        if (ctx.getDeposito() != null) {
            texto += " Para asegurarla pagas un depósito de " + soles(new BigDecimal(ctx.getDeposito()))
                    + (ctx.getPrecio() != null ? " (precio total " + soles(new BigDecimal(ctx.getPrecio())) + ")" : "") + ".";
        }
        texto += " ¿Confirmas?";
        ResumenReserva resumen = new ResumenReserva(UUID.fromString(ctx.getOdontologoId()), ctx.getOdontologoNombre(),
                UUID.fromString(ctx.getServicioId()), ctx.getServicioNombre(), inicio,
                ctx.getPrecio() == null ? null : new BigDecimal(ctx.getPrecio()),
                ctx.getDeposito() == null ? null : new BigDecimal(ctx.getDeposito()));
        List<Opcion> opciones = List.of(
                Opcion.de("Sí, reservar", "confirmar"),
                new Opcion("Elegir otro horario", "elegir_fecha", ctx.getFecha()),
                Opcion.de("Cancelar", "cancelar"));
        return new Vuelta(Intencion.AGENDAR_CITA, respuesta(c, RespuestaChatbot.CONFIRMACION, texto,
                Intencion.AGENDAR_CITA, resumen, List.of("confirmar", "cancelar"), opciones), ctx);
    }

    /** Lo que se está por reservar, para que el frontend lo muestre en la tarjeta de confirmación. */
    public record ResumenReserva(UUID odontologoId, String odontologo, UUID servicioId, String servicio,
                                 OffsetDateTime inicio, BigDecimal precio, BigDecimal deposito) {}

    private Vuelta reservar(Conversacion c, ContextoChat ctx) {
        if (c.rol() == RolUsuario.ODONTOLOGO) {
            return soloPacientes(c);
        }
        OffsetDateTime inicio = OffsetDateTime.parse(ctx.getTurno());
        CitaResponse cita;
        try {
            cita = citaService.reservar(c.usuarioId(), new ReservaRequest(
                    UUID.fromString(ctx.getOdontologoId()), UUID.fromString(ctx.getServicioId()), inicio));
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == HttpStatus.CONFLICT.value() && e.getReason() != null
                    && e.getReason().startsWith("Tienes")) {
                // demasiadas reservas sin pagar: no tiene sentido volver a mostrar turnos
                return menu(c, e.getReason() + ". Puedes verlas en \"Mis citas\".");
            }
            if (e.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                return mostrarTurnos(c, ctx, LocalDate.parse(ctx.getFecha()),
                        "Ese horario se acaba de ocupar 😕. Te muestro los que siguen libres.");
            }
            return menu(c, e.getReason() != null ? e.getReason() + "." : "No pude reservar la cita. Intenta de nuevo.");
        }
        String texto = "¡Listo! Reservé tu cita de " + ctx.getServicioNombre() + " con " + ctx.getOdontologoNombre()
                + " el " + DIA_LARGO.format(inicio.atZoneSameInstant(ZONA)) + " a las " + hora(inicio) + ".";
        if (cita.estado() == EstadoCita.PENDIENTE_PAGO && cita.depositoRequerido() != null) {
            texto += " Para confirmarla paga el depósito de " + soles(cita.depositoRequerido())
                    + (cita.pagarAntesDe() != null ? " antes de las " + hora(cita.pagarAntesDe()) : "")
                    + "; si no, el turno se libera.";
        }
        List<Opcion> opciones = List.of(
                new Opcion("Pagar depósito", "pagar_deposito", cita.id().toString()),
                Opcion.de("Ver mis citas", "ver_historial"));
        return new Vuelta(Intencion.AGENDAR_CITA, respuesta(c, RespuestaChatbot.CITA_RESERVADA, texto,
                Intencion.AGENDAR_CITA, cita, List.of("pagar_deposito", "ver_historial"), opciones), new ContextoChat());
    }

    private Vuelta cancelarFlujo(Conversacion c) {
        return new Vuelta(Intencion.AGENDAR_CITA, respuesta(c, RespuestaChatbot.TEXTO_SIMPLE,
                "Listo, lo dejamos ahí. ¿En qué más te ayudo?", Intencion.AGENDAR_CITA, null, ACCIONES_MENU,
                opcionesMenu()), new ContextoChat());
    }

    private Vuelta soloPacientes(Conversacion c) {
        return new Vuelta(Intencion.AGENDAR_CITA, respuesta(c, RespuestaChatbot.TEXTO_SIMPLE,
                "Agendar por el chat es para pacientes. Tu agenda está en \"Mi consultorio\"; aquí puedes escribir \"mis citas\".",
                Intencion.AGENDAR_CITA, null, List.of("ver_historial"), List.of(Opcion.de("Ver mis citas", "ver_historial"))),
                new ContextoChat());
    }

    // ==================================================================
    // Saludo e historial (citas reales)
    // ==================================================================

    private Vuelta saludar(Conversacion c) {
        String hola = "¡Hola" + (c.nombre().isEmpty() ? "" : ", " + c.nombre()) + "! Soy el asistente de OdontoSystem. ";
        if (c.rol() == RolUsuario.ODONTOLOGO) {
            return new Vuelta(Intencion.SALUDO, respuesta(c, RespuestaChatbot.TEXTO_SIMPLE,
                    hola + "Escribe \"mis citas\" para ver tu agenda de los próximos días.", Intencion.SALUDO, null,
                    List.of("ver_historial"), List.of(Opcion.de("Ver mi agenda", "ver_historial"))), c.contexto());
        }
        List<CitaResponse> citas = citaService.misCitas(c.usuarioId());
        Optional<CitaResponse> proxima = proximas(citas).stream().findFirst();
        String mensaje;
        List<Opcion> opciones;
        if (proxima.isPresent()) {
            mensaje = hola + "Recuerda que tienes una cita " + describir(proxima.get()) + ".";
            if (proxima.get().estado() == EstadoCita.PENDIENTE_PAGO) {
                mensaje += " Aún falta pagar el depósito.";
            }
            opciones = List.of(Opcion.de("Ver mis citas", "ver_historial"), Opcion.de("Agendar otra cita", "agendar_cita"));
        } else {
            Optional<CitaResponse> ultima = ultimaCompletada(citas);
            if (ultima.isPresent() && mesesDesde(ultima.get()) >= MESES_PARA_CONTROL) {
                mensaje = hola + "Tu última visita fue hace " + mesesDesde(ultima.get())
                        + " meses. ¿Agendamos tu control?";
                opciones = List.of(Opcion.de("Sí, agendar", "agendar_cita"), Opcion.de("Buscar odontólogo", "buscar_odontologo"));
            } else if (citas.isEmpty()) {
                mensaje = hola + "¿Buscas agendar tu primera cita? Puedo ayudarte a buscar un odontólogo, "
                        + "agendar una cita o revisar tus citas.";
                opciones = opcionesMenu();
            } else {
                mensaje = hola + "Puedo ayudarte a buscar un odontólogo, agendar una cita o revisar tus citas.";
                opciones = opcionesMenu();
            }
        }
        return new Vuelta(Intencion.SALUDO, respuesta(c, RespuestaChatbot.TEXTO_SIMPLE, mensaje, Intencion.SALUDO, null,
                ACCIONES_MENU, opciones), c.contexto());
    }

    private Vuelta historial(Conversacion c) {
        if (c.rol() == RolUsuario.ODONTOLOGO) {
            OffsetDateTime ahora = OffsetDateTime.now(clock);
            List<CitaResponse> agenda = citaService.agenda(c.usuarioId(), ahora, ahora.plusDays(7)).stream()
                    .filter(a -> ESTADOS_PROXIMOS.contains(a.estado())).toList();
            String mensaje = agenda.isEmpty()
                    ? "No tienes citas en los próximos 7 días."
                    : "Tienes " + agenda.size() + (agenda.size() == 1 ? " cita" : " citas") + " en los próximos 7 días:\n"
                    + String.join("\n", agenda.stream().map(a -> "• " + DIA_CORTO.format(a.inicio().atZoneSameInstant(ZONA))
                    + " " + hora(a.inicio()) + " — " + a.paciente().nombre() + " (" + a.servicio().titulo() + ")").toList());
            return new Vuelta(Intencion.CONSULTAR_HISTORIAL, respuesta(c, RespuestaChatbot.LISTA_CITAS, mensaje,
                    Intencion.CONSULTAR_HISTORIAL, agenda, List.of(), List.of()), c.contexto());
        }
        List<CitaResponse> citas = citaService.misCitas(c.usuarioId());
        if (citas.isEmpty()) {
            return new Vuelta(Intencion.CONSULTAR_HISTORIAL, respuesta(c, RespuestaChatbot.TEXTO_SIMPLE,
                    "Todavía no tienes citas. ¿Agendamos la primera?", Intencion.CONSULTAR_HISTORIAL, null,
                    List.of("agendar_cita"), List.of(Opcion.de("Agendar una cita", "agendar_cita"))), c.contexto());
        }
        List<CitaResponse> proximas = proximas(citas).stream().limit(3).toList();
        Optional<CitaResponse> ultima = ultimaCompletada(citas);
        StringBuilder mensaje = new StringBuilder();
        if (proximas.isEmpty()) {
            mensaje.append("No tienes citas próximas.");
        } else {
            mensaje.append(proximas.size() == 1 ? "Tu próxima cita:" : "Tus próximas citas:");
            for (CitaResponse p : proximas) {
                mensaje.append("\n• ").append(describir(p)).append(estadoLegible(p.estado()));
            }
        }
        if (ultima.isPresent()) {
            mensaje.append("\nTu última visita fue el ")
                    .append(DIA_LARGO.format(ultima.get().inicio().atZoneSameInstant(ZONA)))
                    .append(" con ").append(ultima.get().odontologo().nombre()).append('.');
            if (proximas.isEmpty() && mesesDesde(ultima.get()) >= MESES_PARA_CONTROL) {
                mensaje.append(" Ya pasaron ").append(mesesDesde(ultima.get())).append(" meses: ¿agendamos tu control?");
            }
        }
        List<Opcion> opciones = new ArrayList<>();
        proximas.stream().filter(p -> p.estado() == EstadoCita.PENDIENTE_PAGO).findFirst()
                .ifPresent(p -> opciones.add(new Opcion("Pagar depósito", "pagar_deposito", p.id().toString())));
        opciones.add(Opcion.de("Agendar una cita", "agendar_cita"));
        return new Vuelta(Intencion.CONSULTAR_HISTORIAL, respuesta(c, RespuestaChatbot.LISTA_CITAS, mensaje.toString(),
                Intencion.CONSULTAR_HISTORIAL, proximas, List.of("agendar_cita"), opciones), c.contexto());
    }

    private List<CitaResponse> proximas(List<CitaResponse> citas) {
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        return citas.stream()
                .filter(x -> ESTADOS_PROXIMOS.contains(x.estado()) && x.inicio().isAfter(ahora))
                .sorted(Comparator.comparing(CitaResponse::inicio))
                .toList();
    }

    private static Optional<CitaResponse> ultimaCompletada(List<CitaResponse> citas) {
        return citas.stream().filter(x -> x.estado() == EstadoCita.COMPLETADA)
                .max(Comparator.comparing(CitaResponse::inicio));
    }

    private long mesesDesde(CitaResponse cita) {
        return ChronoUnit.MONTHS.between(cita.inicio().atZoneSameInstant(ZONA).toLocalDate(), hoy());
    }

    private static String describir(CitaResponse cita) {
        return "el " + DIA_LARGO.format(cita.inicio().atZoneSameInstant(ZONA)) + " a las " + hora(cita.inicio())
                + " con " + cita.odontologo().nombre()
                + (cita.servicio() != null ? " (" + cita.servicio().titulo() + ")" : "");
    }

    private static String estadoLegible(EstadoCita estado) {
        return switch (estado) {
            case PENDIENTE_PAGO -> " — falta pagar el depósito";
            case PENDIENTE_CONFIRMACION -> " — esperando que el odontólogo la confirme";
            default -> "";
        };
    }

    // ==================================================================
    // Apoyo
    // ==================================================================

    private Vuelta menu(Conversacion c, String mensaje) {
        return new Vuelta(Intencion.DESCONOCIDA, respuesta(c, RespuestaChatbot.TEXTO_SIMPLE, mensaje,
                Intencion.DESCONOCIDA, null, ACCIONES_MENU, opcionesMenu()), c.contexto());
    }

    /** Vuelve a preguntar lo mismo sin perder lo que el usuario ya eligió. */
    private Vuelta repreguntar(Conversacion c, ContextoChat ctx, String mensaje) {
        return new Vuelta(Intencion.AGENDAR_CITA, respuesta(c, RespuestaChatbot.TEXTO_SIMPLE, mensaje,
                Intencion.AGENDAR_CITA, null, List.of("cancelar"), List.of(Opcion.de("Cancelar", "cancelar"))), ctx);
    }

    private static List<Opcion> opcionesMenu() {
        return List.of(Opcion.de("Buscar odontólogo", "buscar_odontologo"),
                Opcion.de("Agendar cita", "agendar_cita"),
                Opcion.de("Mis citas", "ver_historial"));
    }

    private List<OdontologoTarjeta> todosLosOdontologos() {
        return busquedaService.buscar(
                new FiltroBusqueda(null, null, null, null, null, null, null, "calificacion", 0, 50)).contenido();
    }

    /** Próximos días en que el odontólogo atiende (según su horario semanal), desde hoy. */
    private static List<LocalDate> fechasConHorario(ContextoChat ctx, LocalDate desde, int dias) {
        List<LocalDate> fechas = new ArrayList<>();
        for (int i = 0; i < dias; i++) {
            LocalDate f = desde.plusDays(i);
            String dia = com.odontosystem.OdontoSystem.entity.DiaSemana.values()[f.getDayOfWeek().getValue() - 1].name();
            if (ctx.getDias().isEmpty() || ctx.getDias().contains(dia)) {
                fechas.add(f);
            }
        }
        return fechas;
    }

    /** Disponibilidad de un día; vacío si el odontólogo o el servicio ya no están disponibles. */
    private Optional<DisponibilidadResponse> disponibilidad(ContextoChat ctx, LocalDate fecha) {
        try {
            return Optional.ofNullable(disponibilidadService.disponibilidad(
                    UUID.fromString(ctx.getOdontologoId()), UUID.fromString(ctx.getServicioId()), fecha));
        } catch (ResponseStatusException e) {
            return Optional.empty();
        }
    }

    private LocalDate hoy() {
        return LocalDate.now(clock.withZone(ZONA));
    }

    private static String hora(OffsetDateTime instante) {
        return HORA.format(instante.atZoneSameInstant(ZONA));
    }

    private static String soles(BigDecimal monto) {
        return monto == null ? "" : "S/ " + monto.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static RespuestaChatbot respuesta(Conversacion c, String tipo, String mensaje, Intencion intencion,
                                              Object datos, List<String> acciones, List<Opcion> opciones) {
        return new RespuestaChatbot(c.sesionId(), tipo, mensaje, intencion.name(), datos, acciones, opciones);
    }

    // ==================================================================
    // Sesiones y mensajes (transacciones cortas)
    // ==================================================================

    private Conversacion abrir(UUID usuarioId, MensajeChatRequest request) {
        SesionChat sesion = obtenerOCrearSesion(usuarioId, request);
        Usuario usuario = sesion.getUsuario();
        return new Conversacion(sesion.getId(), usuarioId, primerNombre(usuario),
                usuario == null ? null : usuario.getRol(), ContextoChat.leer(sesion.getContexto()));
    }

    private void guardar(Conversacion c, String textoUsuario, Vuelta vuelta) {
        SesionChat sesion = sesionChatRepository.findById(c.sesionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "La conversación no existe"));
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        guardarMensaje(sesion, TipoEmisorMensaje.USUARIO, textoUsuario, vuelta.intencion(), ahora);
        // 1 ms después: así el historial siempre queda en orden (usuario, luego bot)
        guardarMensaje(sesion, TipoEmisorMensaje.BOT, vuelta.respuesta().mensaje(), vuelta.intencion(), ahora.plusNanos(1_000_000));
        sesion.setContexto(vuelta.contexto().aJson());
        sesion.setActualizadoEn(ahora); // la sesión pasa a ser la más reciente
        sesionChatRepository.save(sesion);
    }

    private SesionChat obtenerOCrearSesion(UUID usuarioId, MensajeChatRequest request) {
        if (request.sesionId() != null) {
            SesionChat sesion = buscarSesionPropia(usuarioId, request.sesionId());
            if (sesion.getEstado() == EstadoChat.ACTIVA) {
                return sesion;
            }
            // Si la conversación ya se cerró, se empieza una nueva en lugar de reabrirla.
            return crearSesion(usuarioId, request.canal());
        }
        return sesionChatRepository
                .findFirstByUsuarioIdAndEstadoOrderByActualizadoEnDesc(usuarioId, EstadoChat.ACTIVA)
                .orElseGet(() -> crearSesion(usuarioId, request.canal()));
    }

    /** Busca la sesión y verifica que sea del usuario: nadie puede leer conversaciones ajenas. */
    private SesionChat buscarSesionPropia(UUID usuarioId, UUID sesionId) {
        SesionChat sesion = sesionChatRepository.findById(sesionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "La conversación no existe"));
        if (sesion.getUsuario() == null || !sesion.getUsuario().getId().equals(usuarioId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Esta conversación no te pertenece");
        }
        return sesion;
    }

    private SesionChat crearSesion(UUID usuarioId, CanalChat canal) {
        SesionChat nueva = SesionChat.builder()
                .usuario(usuarioRepository.getReferenceById(usuarioId)) // referencia sin consultar la BD
                .canal(canal != null ? canal : CanalChat.WEB)
                .build();
        return sesionChatRepository.save(nueva);
    }

    private void guardarMensaje(SesionChat sesion, TipoEmisorMensaje emisor, String texto, Intencion intencion,
                                OffsetDateTime creadoEn) {
        mensajeChatRepository.save(MensajeChat.builder()
                .sesion(sesion)
                .tipoEmisor(emisor)
                .textoMensaje(texto)
                .intencionDetectada(intencion.name())
                .creadoEn(creadoEn)
                .build());
    }

    private static String primerNombre(Usuario usuario) {
        if (usuario == null || usuario.getNombreCompleto() == null || usuario.getNombreCompleto().isBlank()) {
            return "";
        }
        return usuario.getNombreCompleto().trim().split("\\s+")[0];
    }
}

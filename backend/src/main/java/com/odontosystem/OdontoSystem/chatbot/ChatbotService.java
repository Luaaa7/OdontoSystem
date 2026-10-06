package com.odontosystem.OdontoSystem.chatbot;

import com.odontosystem.OdontoSystem.entity.CanalChat;
import com.odontosystem.OdontoSystem.entity.EstadoChat;
import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.MensajeChat;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.SesionChat;
import com.odontosystem.OdontoSystem.entity.TipoEmisorMensaje;
import com.odontosystem.OdontoSystem.repository.MensajeChatRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.SesionChatRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orquesta una vuelta de conversación:
 * mensaje -> intención + distrito -> memoria de contexto -> guardar -> responder -> guardar.
 *
 * Memoria de contexto: si el bot acaba de preguntar por un distrito (su último mensaje fue de
 * BUSCAR_ODONTOLOGO o AGENDAR_CITA) y el usuario responde solo "Parcona", el mensaje no tiene
 * intención propia, pero se entiende como la respuesta a esa pregunta.
 *
 * Pendiente (Fase 3b): CONSULTAR_HISTORIAL con citas reales y AGENDAR_CITA con disponibilidad,
 * cuando exista la entidad Cita (módulo de citas).
 */
@Service
@RequiredArgsConstructor
public class ChatbotService {

    static final List<String> ACCIONES_MENU = List.of("buscar_odontologo", "agendar_cita", "ver_historial");
    static final int MAX_RESULTADOS = 5;

    /** Intenciones del bot que terminan preguntando "¿en qué distrito?". */
    private static final Set<String> INTENCIONES_QUE_PIDEN_DISTRITO =
            Set.of(Intencion.BUSCAR_ODONTOLOGO.name(), Intencion.AGENDAR_CITA.name());

    private final IntentDetector intentDetector;
    private final DistritoExtractor distritoExtractor;
    private final SesionChatRepository sesionChatRepository;
    private final MensajeChatRepository mensajeChatRepository;
    private final UsuarioRepository usuarioRepository;
    private final PerfilOdontologoRepository perfilOdontologoRepository;

    @Transactional
    public RespuestaChatbot procesarMensaje(UUID usuarioId, MensajeChatRequest request) {
        SesionChat sesion = obtenerOCrearSesion(usuarioId, request);

        // Se lee ANTES de guardar el mensaje nuevo: es lo último que dijo el bot.
        Optional<String> ultimaIntencionDelBot = ultimaIntencionDelBot(sesion);

        Intencion intencion = intentDetector.detectar(request.mensaje());
        Optional<String> distrito = distritoExtractor.extraer(request.mensaje());

        if (intencion == Intencion.DESCONOCIDA && distrito.isPresent()
                && ultimaIntencionDelBot.filter(INTENCIONES_QUE_PIDEN_DISTRITO::contains).isPresent()) {
            intencion = Intencion.BUSCAR_ODONTOLOGO; // "Parcona" responde a "¿en qué distrito?"
        }

        guardarMensaje(sesion, TipoEmisorMensaje.USUARIO, request.mensaje(), intencion);
        RespuestaChatbot respuesta = generarRespuesta(sesion, intencion, distrito);
        guardarMensaje(sesion, TipoEmisorMensaje.BOT, respuesta.mensaje(), intencion);

        sesion.setActualizadoEn(OffsetDateTime.now()); // la sesión pasa a ser la más reciente
        return respuesta;
    }

    @Transactional(readOnly = true)
    public List<MensajeChatResponse> obtenerHistorial(UUID usuarioId, UUID sesionId) {
        buscarSesionPropia(usuarioId, sesionId);
        return mensajeChatRepository.findBySesionIdOrderByCreadoEnAsc(sesionId).stream()
                .map(MensajeChatResponse::desde)
                .toList();
    }

    // ------------------------------------------------------------------ sesiones

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

    private Optional<String> ultimaIntencionDelBot(SesionChat sesion) {
        return mensajeChatRepository
                .findFirstBySesionIdAndTipoEmisorOrderByCreadoEnDesc(sesion.getId(), TipoEmisorMensaje.BOT)
                .map(MensajeChat::getIntencionDetectada);
    }

    private void guardarMensaje(SesionChat sesion, TipoEmisorMensaje emisor, String texto, Intencion intencion) {
        mensajeChatRepository.save(MensajeChat.builder()
                .sesion(sesion)
                .tipoEmisor(emisor)
                .textoMensaje(texto)
                .intencionDetectada(intencion.name())
                .build());
    }

    // ------------------------------------------------------------------ respuestas

    private RespuestaChatbot generarRespuesta(SesionChat sesion, Intencion intencion, Optional<String> distrito) {
        return switch (intencion) {
            case SALUDO -> texto(sesion, intencion,
                    "¡Hola, " + primerNombre(sesion) + "! Soy el asistente de OdontoSystem. "
                            + "Puedo ayudarte a buscar un odontólogo, agendar una cita o revisar tus citas.",
                    ACCIONES_MENU);
            case BUSCAR_ODONTOLOGO, AGENDAR_CITA -> distrito
                    .map(d -> buscarEnDistrito(sesion, intencion, d))
                    .orElseGet(() -> preguntarDistrito(sesion, intencion));
            // TODO Fase 3b: consultar la última y la próxima cita del paciente.
            case CONSULTAR_HISTORIAL -> texto(sesion, intencion,
                    "Pronto podrás revisar tus citas desde aquí. Mientras tanto, ¿quieres buscar un odontólogo?",
                    List.of("buscar_odontologo"));
            case DESCONOCIDA -> texto(sesion, intencion,
                    "No entendí bien eso. Puedo ayudarte a:\n"
                            + "🔍 Buscar un odontólogo\n"
                            + "📅 Agendar una cita\n"
                            + "🕐 Ver tu historial de citas",
                    ACCIONES_MENU);
        };
    }

    private RespuestaChatbot preguntarDistrito(SesionChat sesion, Intencion intencion) {
        String mensaje = intencion == Intencion.AGENDAR_CITA
                ? "Para agendar, primero elige un odontólogo. ¿En qué distrito lo buscamos?"
                : "¿En qué distrito de Ica te gustaría atenderte? "
                  + "Por ejemplo: Ica Centro, Parcona, La Tinguiña o Subtanjalla.";
        return texto(sesion, intencion, mensaje, List.of("elegir_distrito"));
    }

    /**
     * Odontólogos verificados y activos del distrito, mejor calificados primero.
     * El distrito del perfil es texto libre, por eso se compara normalizado en Java
     * (con pocos odontólogos por provincia es suficiente; si crece, conviene una columna normalizada).
     */
    private RespuestaChatbot buscarEnDistrito(SesionChat sesion, Intencion intencion, String distrito) {
        List<OdontologoResumen> encontrados = perfilOdontologoRepository
                .findByDesactivadoEnIsNullAndEstadoVerificacionAndDistritoConsultorioIsNotNull(
                        EstadoVerificacionOdontologo.VERIFICADO)
                .stream()
                .filter(perfil -> distritoExtractor.coincide(perfil.getDistritoConsultorio(), distrito))
                .sorted(Comparator.comparing(PerfilOdontologo::getCalificacionPromedio,
                        Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())))
                .limit(MAX_RESULTADOS)
                .map(OdontologoResumen::desde)
                .toList();

        if (encontrados.isEmpty()) {
            return texto(sesion, intencion,
                    "Todavía no tengo odontólogos verificados en " + distrito + ". ¿Quieres buscar en otro distrito?",
                    List.of("elegir_distrito"));
        }
        String mensaje = encontrados.size() == 1
                ? "Encontré 1 odontólogo en " + distrito + ":"
                : "Encontré " + encontrados.size() + " odontólogos en " + distrito + ":";
        return new RespuestaChatbot(sesion.getId(), RespuestaChatbot.LISTA_ODONTOLOGOS, mensaje,
                intencion.name(), encontrados, List.of("ver_disponibilidad"));
    }

    private static RespuestaChatbot texto(SesionChat sesion, Intencion intencion, String mensaje, List<String> acciones) {
        return new RespuestaChatbot(sesion.getId(), RespuestaChatbot.TEXTO_SIMPLE, mensaje, intencion.name(), null, acciones);
    }

    private static String primerNombre(SesionChat sesion) {
        if (sesion.getUsuario() == null || sesion.getUsuario().getNombreCompleto() == null) {
            return "";
        }
        return sesion.getUsuario().getNombreCompleto().trim().split("\\s+")[0];
    }
}

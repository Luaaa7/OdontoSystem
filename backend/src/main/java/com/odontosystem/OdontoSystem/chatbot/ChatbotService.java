package com.odontosystem.OdontoSystem.chatbot;

import com.odontosystem.OdontoSystem.entity.CanalChat;
import com.odontosystem.OdontoSystem.entity.EstadoChat;
import com.odontosystem.OdontoSystem.entity.MensajeChat;
import com.odontosystem.OdontoSystem.entity.SesionChat;
import com.odontosystem.OdontoSystem.entity.TipoEmisorMensaje;
import com.odontosystem.OdontoSystem.repository.MensajeChatRepository;
import com.odontosystem.OdontoSystem.repository.SesionChatRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Orquesta una vuelta de conversación:
 * mensaje del usuario -> detectar intención -> guardar -> generar respuesta -> guardar -> responder.
 *
 * Fase 2: las respuestas son guiadas pero todavía no consultan odontólogos ni citas.
 * Fase 3: BUSCAR_ODONTOLOGO, AGENDAR_CITA y CONSULTAR_HISTORIAL usarán datos reales y la
 * memoria de contexto (lo último que preguntó el bot en la sesión).
 */
@Service
@RequiredArgsConstructor
public class ChatbotService {

    static final List<String> ACCIONES_MENU = List.of("buscar_odontologo", "agendar_cita", "ver_historial");

    private final IntentDetector intentDetector;
    private final SesionChatRepository sesionChatRepository;
    private final MensajeChatRepository mensajeChatRepository;
    private final UsuarioRepository usuarioRepository;

    @Transactional
    public RespuestaChatbot procesarMensaje(UUID usuarioId, MensajeChatRequest request) {
        SesionChat sesion = obtenerOCrearSesion(usuarioId, request);
        Intencion intencion = intentDetector.detectar(request.mensaje());

        guardarMensaje(sesion, TipoEmisorMensaje.USUARIO, request.mensaje(), intencion);
        RespuestaChatbot respuesta = generarRespuesta(sesion, intencion);
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

    private void guardarMensaje(SesionChat sesion, TipoEmisorMensaje emisor, String texto, Intencion intencion) {
        mensajeChatRepository.save(MensajeChat.builder()
                .sesion(sesion)
                .tipoEmisor(emisor)
                .textoMensaje(texto)
                .intencionDetectada(intencion.name())
                .build());
    }

    // ------------------------------------------------------------------ respuestas

    private RespuestaChatbot generarRespuesta(SesionChat sesion, Intencion intencion) {
        return switch (intencion) {
            case SALUDO -> texto(sesion, intencion,
                    "¡Hola, " + primerNombre(sesion) + "! Soy el asistente de OdontoSystem. "
                            + "Puedo ayudarte a buscar un odontólogo, agendar una cita o revisar tus citas.",
                    ACCIONES_MENU);
            case BUSCAR_ODONTOLOGO -> texto(sesion, intencion,
                    "¿En qué distrito de Ica te gustaría atenderte? "
                            + "Por ejemplo: Ica Centro, Parcona, La Tinguiña o Subtanjalla.",
                    List.of("elegir_distrito"));
            case AGENDAR_CITA -> texto(sesion, intencion,
                    "Para agendar, primero elige un odontólogo. ¿En qué distrito lo buscamos?",
                    List.of("elegir_distrito"));
            // TODO Fase 3: consultar la última y la próxima cita del paciente.
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

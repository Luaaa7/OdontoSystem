package com.odontosystem.OdontoSystem.chatbot;

import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints del chatbot. Requieren JWT (header Authorization: Bearer ...):
 * el usuario se toma del token, nunca del cuerpo del mensaje.
 */
@Tag(name = "Chatbot", description = "Asistente conversacional (motor de reglas con memoria de contexto)")
@RestController
@RequestMapping("/api/chatbot")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotService chatbotService;

    @Operation(summary = "Enviar un mensaje al chatbot",
            description = "Detecta la intención, guarda la conversación y responde. Sin sesion_id continúa la conversación activa o crea una nueva.")
    @PostMapping("/mensaje")
    public RespuestaChatbot enviarMensaje(@AuthenticationPrincipal UsuarioPrincipal principal,
                                          @Valid @RequestBody MensajeChatRequest request) {
        return chatbotService.procesarMensaje(principal.getUsuario().getId(), request);
    }

    @Operation(summary = "Historial de una conversación",
            description = "Mensajes del usuario y del bot en orden. Solo el dueño de la conversación puede verla.")
    @GetMapping("/sesiones/{sesionId}/mensajes")
    public List<MensajeChatResponse> historial(@AuthenticationPrincipal UsuarioPrincipal principal,
                                               @PathVariable UUID sesionId) {
        return chatbotService.obtenerHistorial(principal.getUsuario().getId(), sesionId);
    }
}

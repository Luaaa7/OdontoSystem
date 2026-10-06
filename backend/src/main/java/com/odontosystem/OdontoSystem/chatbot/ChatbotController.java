package com.odontosystem.OdontoSystem.chatbot;

import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
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
@RestController
@RequestMapping("/api/chatbot")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotService chatbotService;

    @PostMapping("/mensaje")
    public RespuestaChatbot enviarMensaje(@AuthenticationPrincipal UsuarioPrincipal principal,
                                          @Valid @RequestBody MensajeChatRequest request) {
        return chatbotService.procesarMensaje(principal.getUsuario().getId(), request);
    }

    @GetMapping("/sesiones/{sesionId}/mensajes")
    public List<MensajeChatResponse> historial(@AuthenticationPrincipal UsuarioPrincipal principal,
                                               @PathVariable UUID sesionId) {
        return chatbotService.obtenerHistorial(principal.getUsuario().getId(), sesionId);
    }
}

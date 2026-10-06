package com.odontosystem.OdontoSystem.chatbot;

import com.odontosystem.OdontoSystem.entity.CanalChat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Lo que envía el ChatWidget (web) o la app (móvil). En JSON (snake_case):
 * { "mensaje": "busco un dentista", "sesion_id": "opcional", "canal": "WEB" }
 */
public record MensajeChatRequest(
        @NotBlank(message = "El mensaje no puede estar vacío")
        @Size(max = 1000, message = "El mensaje no puede superar los 1000 caracteres")
        String mensaje,
        UUID sesionId,   // null = continuar la conversación activa o empezar una nueva
        CanalChat canal  // null = WEB
) {}

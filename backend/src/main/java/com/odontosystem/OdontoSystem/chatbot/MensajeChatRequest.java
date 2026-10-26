package com.odontosystem.OdontoSystem.chatbot;

import com.odontosystem.OdontoSystem.entity.CanalChat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Lo que envía el ChatWidget (web) o la app (móvil). En JSON (snake_case):
 * <pre>
 * { "mensaje": "busco un dentista en Parcona", "sesion_id": "opcional", "canal": "WEB" }
 * </pre>
 * Cuando el usuario pulsa un botón (una "opcion" de la respuesta anterior), se manda además
 * la acción y el valor del botón, y como mensaje el texto del botón:
 * <pre>
 * { "mensaje": "Dra. Ana Torres", "accion": "elegir_odontologo", "valor": "uuid-del-odontologo" }
 * </pre>
 */
public record MensajeChatRequest(
        @NotBlank(message = "El mensaje no puede estar vacío")
        @Size(max = 1000, message = "El mensaje no puede superar los 1000 caracteres")
        String mensaje,
        UUID sesionId,   // null = continuar la conversación activa o empezar una nueva
        CanalChat canal, // null = WEB
        @Size(max = 40) String accion, // null = texto escrito por el usuario
        @Size(max = 100) String valor
) {}

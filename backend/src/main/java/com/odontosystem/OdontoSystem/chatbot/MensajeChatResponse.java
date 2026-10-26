package com.odontosystem.OdontoSystem.chatbot;

import com.odontosystem.OdontoSystem.entity.MensajeChat;
import com.odontosystem.OdontoSystem.entity.TipoEmisorMensaje;

import java.time.OffsetDateTime;

/** Un mensaje del historial, tal como lo recibe el frontend para redibujar la conversación. */
public record MensajeChatResponse(
        TipoEmisorMensaje emisor,
        String texto,
        String intencion,
        OffsetDateTime creadoEn
) {
    static MensajeChatResponse desde(MensajeChat mensaje) {
        return new MensajeChatResponse(
                mensaje.getTipoEmisor(),
                mensaje.getTextoMensaje(),
                mensaje.getIntencionDetectada(),
                mensaje.getCreadoEn()
        );
    }
}

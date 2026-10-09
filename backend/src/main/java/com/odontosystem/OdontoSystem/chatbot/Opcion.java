package com.odontosystem.OdontoSystem.chatbot;

/**
 * Un botón que el frontend puede mostrar debajo de la respuesta del bot.
 * Al pulsarlo, el frontend manda de nuevo a /api/chatbot/mensaje:
 * { "mensaje": etiqueta, "accion": accion, "valor": valor }.
 * Así la conversación queda legible en el historial y el backend no tiene que adivinar.
 */
public record Opcion(String etiqueta, String accion, String valor) {

    public static Opcion de(String etiqueta, String accion) {
        return new Opcion(etiqueta, accion, null);
    }
}

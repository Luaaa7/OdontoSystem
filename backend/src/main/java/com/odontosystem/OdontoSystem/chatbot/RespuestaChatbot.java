package com.odontosystem.OdontoSystem.chatbot;

import java.util.List;
import java.util.UUID;

/**
 * Respuesta del chatbot. El campo "tipo" le dice al frontend cómo dibujarla
 * (ver docs/arbol-conversacion-chatbot.md): texto_simple, lista_odontologos,
 * lista_horarios o confirmacion.
 */
public record RespuestaChatbot(
        UUID sesionId,
        String tipo,
        String mensaje,
        String intencion,
        Object datos,          // se omite en el JSON cuando es null
        List<String> acciones  // botones sugeridos para el frontend
) {
    public static final String TEXTO_SIMPLE = "texto_simple";
    public static final String LISTA_ODONTOLOGOS = "lista_odontologos";
    public static final String LISTA_HORARIOS = "lista_horarios";
    public static final String CONFIRMACION = "confirmacion";
}

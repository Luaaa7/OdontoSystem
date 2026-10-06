package com.odontosystem.OdontoSystem.chatbot;

/**
 * Intenciones que reconoce el motor conversacional de OdontoSystem.
 * El nombre del enum es el que se guarda en mensajes_chat.intencion_detectada.
 */
public enum Intencion {
    /** "hola", "buenos días": el servicio responde según el historial del paciente. */
    SALUDO,
    /** "busco un odontólogo", "dentista en Parcona", "quiero una cita". */
    BUSCAR_ODONTOLOGO,
    /** "ver disponibilidad", "agendar con el Dr. Pérez", "reservar para el lunes". */
    AGENDAR_CITA,
    /** "mis citas", "mi historial", "cuándo es mi próxima cita". */
    CONSULTAR_HISTORIAL,
    /** No se reconoció ninguna intención: el bot muestra el menú de ayuda. */
    DESCONOCIDA
}

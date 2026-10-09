package com.odontosystem.OdontoSystem.chatbot;

import java.util.List;
import java.util.UUID;

/**
 * Respuesta del chatbot. El campo "tipo" le dice al frontend cómo dibujarla
 * (ver docs/arbol-conversacion-chatbot.md).
 */
public record RespuestaChatbot(
        UUID sesionId,
        String tipo,
        String mensaje,
        String intencion,
        Object datos,           // se omite en el JSON cuando es null
        List<String> acciones,  // nombres de las acciones sugeridas (compatibilidad)
        List<Opcion> opciones   // botones listos para mostrar: etiqueta + acción + valor
) {
    public static final String TEXTO_SIMPLE = "texto_simple";
    public static final String LISTA_ODONTOLOGOS = "lista_odontologos";
    public static final String LISTA_SERVICIOS = "lista_servicios";
    public static final String LISTA_HORARIOS = "lista_horarios";
    /** "¿Confirmas la reserva?": datos trae el resumen de lo que se va a reservar. */
    public static final String CONFIRMACION = "confirmacion";
    /** La cita se creó (PENDIENTE_PAGO): datos trae la cita y hay que pagar el depósito. */
    public static final String CITA_RESERVADA = "cita_reservada";
    public static final String LISTA_CITAS = "lista_citas";
}

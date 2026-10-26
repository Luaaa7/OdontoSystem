package com.odontosystem.OdontoSystem.chatbot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Memoria de contexto de una conversación (columna sesiones_chat.contexto, JSONB).
 *
 * Guarda en qué paso del agendamiento va el usuario y qué eligió, para entender respuestas
 * cortas en el siguiente mensaje: "1" (el primero de la lista que se le mostró), "a las 10",
 * "¿y el lunes?", "sí". Los ids y fechas se guardan como texto para que el JSON sea simple
 * y fácil de leer si alguien revisa la tabla.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContextoChat {

    /** Qué está esperando el bot. null = no hay ningún flujo en curso. */
    public enum Paso { ELEGIR_DISTRITO, ELEGIR_ODONTOLOGO, ELEGIR_SERVICIO, ELEGIR_TURNO, CONFIRMAR }

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Paso paso;
    private String distrito;
    /** Ids de los odontólogos que se mostraron, en el mismo orden ("el 2" = el segundo). */
    private List<String> odontologos = new ArrayList<>();
    private String odontologoId;
    private String odontologoNombre;
    /** Días de la semana con horario del odontólogo elegido (LUNES, MARTES...). */
    private List<String> dias = new ArrayList<>();
    private List<String> servicios = new ArrayList<>();
    private String servicioId;
    private String servicioNombre;
    private String precio;
    private String deposito;
    /** Fecha (yyyy-MM-dd) cuyos turnos se mostraron. */
    private String fecha;
    /** Inicio (ISO con zona) de cada turno mostrado, en orden. */
    private List<String> turnos = new ArrayList<>();
    /** Turno elegido, pendiente de confirmar. */
    private String turno;

    public static ContextoChat leer(String json) {
        if (json == null || json.isBlank()) {
            return new ContextoChat();
        }
        try {
            return JSON.readValue(json, ContextoChat.class);
        } catch (Exception e) {
            // Un contexto ilegible no debe romper el chat: se empieza de cero.
            return new ContextoChat();
        }
    }

    public String aJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }
}

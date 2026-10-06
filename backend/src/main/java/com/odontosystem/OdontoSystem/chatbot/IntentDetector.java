package com.odontosystem.OdontoSystem.chatbot;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Motor de reglas para detectar la intención del usuario a partir de texto libre.
 *
 * Funcionamiento:
 *  1. Normaliza el texto (minúsculas, sin tildes ni signos, espacios simples).
 *  2. Busca frases clave de cada intención como palabras completas
 *     ("cita" no coincide dentro de "citación", por ejemplo).
 *  3. Suma los pesos de las frases encontradas y gana la intención con más puntaje.
 *     Las frases largas pesan más que las palabras sueltas, porque son más específicas:
 *     "mis citas" (historial) le gana a "cita" (búsqueda).
 *  4. En empate, gana la intención más concreta según PRIORIDAD_DESEMPATE.
 *  5. Si nada coincide, devuelve DESCONOCIDA.
 */
@Component
public class IntentDetector {

    /** Peso de cada frase clave. Una frase de 2+ palabras es más específica que una palabra suelta. */
    private record FraseClave(String frase, int peso) {}

    private static FraseClave fuerte(String frase) { return new FraseClave(frase, 3); }
    private static FraseClave media(String frase)  { return new FraseClave(frase, 2); }
    private static FraseClave debil(String frase)  { return new FraseClave(frase, 1); }

    private static final Map<Intencion, List<FraseClave>> REGLAS = new EnumMap<>(Intencion.class);

    static {
        REGLAS.put(Intencion.CONSULTAR_HISTORIAL, List.of(
                fuerte("mis citas"), fuerte("mi cita"), fuerte("mi historial"),
                fuerte("ultima cita"), fuerte("proxima cita"), fuerte("ultima visita"),
                fuerte("citas anteriores"), fuerte("citas pendientes"), fuerte("cuando es mi"),
                fuerte("tengo cita"), fuerte("tengo una cita"), fuerte("citas agendadas"),
                media("historial"), media("recordatorio"), media("recordar")
        ));
        REGLAS.put(Intencion.AGENDAR_CITA, List.of(
                fuerte("ver disponibilidad"), fuerte("agendar con"), fuerte("reservar con"),
                fuerte("cita con"), fuerte("horarios disponibles"), fuerte("que horarios"),
                fuerte("separar una cita"), fuerte("separar cita"), fuerte("confirmar cita"),
                media("agendar"), media("agenda"), media("reservar"), media("reserva"),
                media("disponibilidad"), media("disponible"), media("horario"), media("horarios"),
                media("turno"), media("turnos"),
                debil("lunes"), debil("martes"), debil("miercoles"), debil("jueves"),
                debil("viernes"), debil("sabado"), debil("domingo"), debil("manana"),
                debil("hoy"), debil("tarde")
        ));
        REGLAS.put(Intencion.BUSCAR_ODONTOLOGO, List.of(
                fuerte("busco un odontologo"), fuerte("buscar odontologo"), fuerte("busco odontologo"),
                fuerte("busco un dentista"), fuerte("quiero una cita"), fuerte("necesito una cita"),
                fuerte("sacar una cita"), fuerte("sacar cita"), fuerte("cerca de mi"),
                media("odontologo"), media("odontologos"), media("odontologa"), media("odontologas"),
                media("dentista"), media("dentistas"), media("especialista"), media("especialistas"),
                media("busco"), media("buscar"), media("cerca"), media("distrito"),
                media("ortodoncia"), media("endodoncia"), media("limpieza"), media("brackets"),
                media("implante"), media("implantes"), media("extraccion"), media("caries"),
                media("muela"), media("muelas"), media("diente"), media("dientes"), media("dolor"),
                debil("cita"), debil("doctor"), debil("doctora"), debil("consulta")
        ));
        REGLAS.put(Intencion.SALUDO, List.of(
                fuerte("buenos dias"), fuerte("buenas tardes"), fuerte("buenas noches"),
                media("hola"), media("buenas"), media("saludos"), media("hey")
        ));
    }

    /** En empate gana la intención que aparece primero (la más concreta). */
    private static final List<Intencion> PRIORIDAD_DESEMPATE = List.of(
            Intencion.CONSULTAR_HISTORIAL,
            Intencion.AGENDAR_CITA,
            Intencion.BUSCAR_ODONTOLOGO,
            Intencion.SALUDO
    );

    public Intencion detectar(String mensaje) {
        String texto = normalizar(mensaje);
        if (texto.isEmpty()) {
            return Intencion.DESCONOCIDA;
        }
        String conBordes = " " + texto + " ";

        Intencion mejor = Intencion.DESCONOCIDA;
        int mejorPuntaje = 0;
        for (Intencion intencion : PRIORIDAD_DESEMPATE) {
            int puntaje = 0;
            for (FraseClave clave : REGLAS.get(intencion)) {
                if (conBordes.contains(" " + clave.frase() + " ")) {
                    puntaje += clave.peso();
                }
            }
            if (puntaje > mejorPuntaje) { // ">" estricto: en empate se queda la de mayor prioridad
                mejor = intencion;
                mejorPuntaje = puntaje;
            }
        }
        return mejor;
    }

    /** "¿Cuándo es mi PRÓXIMA cita?" -> "cuando es mi proxima cita" */
    static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")          // quita tildes y diéresis
                .toLowerCase()
                .replaceAll("[^a-z0-9ñ ]", " ")    // signos de puntuación -> espacio
                .replaceAll("\\s+", " ")
                .trim();
    }
}

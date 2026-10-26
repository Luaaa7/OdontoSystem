package com.odontosystem.OdontoSystem.chatbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas del motor de reglas. No necesitan Spring ni base de datos: son instantáneas.
 * Meta del proyecto (A2): más del 90% de intenciones correctamente identificadas.
 */
class IntentDetectorTest {

    private final IntentDetector detector = new IntentDetector();

    @DisplayName("Detecta la intención correcta en frases típicas de pacientes")
    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            # ---- BUSCAR_ODONTOLOGO ----
            Busco un odontólogo                              | BUSCAR_ODONTOLOGO
            Quiero una cita                                  | BUSCAR_ODONTOLOGO
            Odontólogos cerca de mí                          | BUSCAR_ODONTOLOGO
            necesito un dentista en Parcona                  | BUSCAR_ODONTOLOGO
            hola, busco un dentista en La Tinguiña           | BUSCAR_ODONTOLOGO
            me duele una muela                               | BUSCAR_ODONTOLOGO
            ¿Hay especialistas en ortodoncia?                | BUSCAR_ODONTOLOGO
            quiero ponerme brackets                          | BUSCAR_ODONTOLOGO
            necesito una cita para mañana                    | BUSCAR_ODONTOLOGO
            # ---- AGENDAR_CITA ----
            Ver disponibilidad                               | AGENDAR_CITA
            quiero agendar con el Dr. Pérez                  | AGENDAR_CITA
            quiero agendar una cita                          | AGENDAR_CITA
            ¿Qué horarios tiene la doctora Gómez?            | AGENDAR_CITA
            reservar con la Dra. Ruiz el lunes               | AGENDAR_CITA
            cita con el doctor Pérez el martes               | AGENDAR_CITA
            ¿tiene turnos disponibles el viernes?            | AGENDAR_CITA
            # ---- CONSULTAR_HISTORIAL ----
            mis citas                                        | CONSULTAR_HISTORIAL
            ¿Cuándo es mi PRÓXIMA cita?                      | CONSULTAR_HISTORIAL
            quiero ver mi historial                          | CONSULTAR_HISTORIAL
            ¿tengo cita mañana?                              | CONSULTAR_HISTORIAL
            cuál fue mi última visita                        | CONSULTAR_HISTORIAL
            muéstrame mis citas pendientes                   | CONSULTAR_HISTORIAL
            # ---- SALUDO ----
            hola                                             | SALUDO
            Buenos días                                      | SALUDO
            buenas tardes!!                                  | SALUDO
            # ---- DESCONOCIDA ----
            gracias                                          | DESCONOCIDA
            asdfgh                                           | DESCONOCIDA
            ¿cuál es la capital de Francia?                  | DESCONOCIDA
            """)
    void detectaIntencion(String mensaje, Intencion esperada) {
        assertThat(detector.detectar(mensaje)).isEqualTo(esperada);
    }

    @DisplayName("Mensajes vacíos o nulos son DESCONOCIDA")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "?!.,"})
    void vacioEsDesconocida(String mensaje) {
        assertThat(detector.detectar(mensaje)).isEqualTo(Intencion.DESCONOCIDA);
    }

    @Test
    @DisplayName("Coincide por palabra completa, no por fragmentos")
    void coincidePorPalabraCompleta() {
        // "citación" contiene "cita" pero no es la palabra "cita"
        assertThat(detector.detectar("recibí una citación")).isEqualTo(Intencion.DESCONOCIDA);
        // "hola" dentro de "holanda" no es un saludo
        assertThat(detector.detectar("viajo a holanda")).isEqualTo(Intencion.DESCONOCIDA);
    }

    @Test
    @DisplayName("Normaliza tildes, mayúsculas y signos")
    void normalizaTexto() {
        assertThat(IntentDetector.normalizar("¿Cuándo es mi PRÓXIMA cita?"))
                .isEqualTo("cuando es mi proxima cita");
        assertThat(IntentDetector.normalizar("  La   Tinguiña!! "))
                .isEqualTo("la tinguina");
    }
}

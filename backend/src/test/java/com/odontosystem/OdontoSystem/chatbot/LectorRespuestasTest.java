package com.odontosystem.OdontoSystem.chatbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Respuestas cortas dentro de la conversación. "Hoy" en estas pruebas es el viernes 9/10/2026. */
class LectorRespuestasTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 9);

    private static String n(String texto) {
        return IntentDetector.normalizar(texto);
    }

    @Test
    @DisplayName("Posición en la lista: número, ordinal o \"el último\"")
    void indice() {
        assertThat(LectorRespuestas.indice(n("2"), 3)).contains(1);
        assertThat(LectorRespuestas.indice(n("el 1"), 3)).contains(0);
        assertThat(LectorRespuestas.indice(n("el segundo"), 3)).contains(1);
        assertThat(LectorRespuestas.indice(n("la última"), 3)).contains(2);
        assertThat(LectorRespuestas.indice(n("9"), 3)).isEmpty();
        assertThat(LectorRespuestas.indice(n("no sé"), 3)).isEmpty();
    }

    @Test
    @DisplayName("Fechas relativas: hoy, mañana, pasado mañana y días de la semana")
    void fechasRelativas() {
        assertThat(LectorRespuestas.fecha("hoy", HOY)).contains(HOY);
        assertThat(LectorRespuestas.fecha("mañana", HOY)).contains(LocalDate.of(2026, 10, 10));
        assertThat(LectorRespuestas.fecha("pasado mañana", HOY)).contains(LocalDate.of(2026, 10, 11));
        assertThat(LectorRespuestas.fecha("¿y el lunes?", HOY)).contains(LocalDate.of(2026, 10, 12));
        assertThat(LectorRespuestas.fecha("el viernes", HOY)).contains(HOY);
        assertThat(LectorRespuestas.fecha("el próximo viernes", HOY)).contains(LocalDate.of(2026, 10, 16));
    }

    @Test
    @DisplayName("Fechas escritas: 12/10, 15 de octubre; si ya pasó este año, es el siguiente")
    void fechasEscritas() {
        assertThat(LectorRespuestas.fecha("el 12/10", HOY)).contains(LocalDate.of(2026, 10, 12));
        assertThat(LectorRespuestas.fecha("15 de octubre", HOY)).contains(LocalDate.of(2026, 10, 15));
        assertThat(LectorRespuestas.fecha("5/1", HOY)).contains(LocalDate.of(2027, 1, 5));
        assertThat(LectorRespuestas.fecha("31/02", HOY)).isEmpty();
    }

    @Test
    @DisplayName("\"En la mañana\" es una hora del día, no la fecha de mañana")
    void enLaMananaNoEsFecha() {
        assertThat(LectorRespuestas.fecha("en la mañana", HOY)).isEmpty();
    }

    @Test
    @DisplayName("Horas: 10:30, 3 pm, a las 10, a las 4 de la tarde; \"a las 3\" es de la tarde")
    void horas() {
        assertThat(LectorRespuestas.hora("10:30")).contains(LocalTime.of(10, 30));
        assertThat(LectorRespuestas.hora("3 pm")).contains(LocalTime.of(15, 0));
        assertThat(LectorRespuestas.hora("a las 10")).contains(LocalTime.of(10, 0));
        assertThat(LectorRespuestas.hora("a las 4 de la tarde")).contains(LocalTime.of(16, 0));
        assertThat(LectorRespuestas.hora("a las 3")).contains(LocalTime.of(15, 0));
        assertThat(LectorRespuestas.hora("9 a.m.")).contains(LocalTime.of(9, 0));
    }

    @Test
    @DisplayName("Una fecha o un número suelto no se confunden con una hora")
    void noEsHora() {
        assertThat(LectorRespuestas.hora("12/10")).isEmpty();
        assertThat(LectorRespuestas.hora("2")).isEmpty();
    }

    @Test
    @DisplayName("Sí, no y cancelar")
    void siNoCancelar() {
        assertThat(LectorRespuestas.esAfirmacion(n("Sí, confirmo"))).isTrue();
        assertThat(LectorRespuestas.esAfirmacion(n("dale"))).isTrue();
        assertThat(LectorRespuestas.esAfirmacion(n("sigo buscando"))).isFalse();
        assertThat(LectorRespuestas.esNegacion(n("no, gracias"))).isTrue();
        assertThat(LectorRespuestas.esCancelacion(n("Cancelar"))).isTrue();
        assertThat(LectorRespuestas.esCancelacion(n("volver al menú"))).isTrue();
        assertThat(LectorRespuestas.esCancelacion(n("10"))).isFalse();
    }
}

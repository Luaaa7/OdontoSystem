package com.odontosystem.OdontoSystem.chatbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DistritoExtractorTest {

    private final DistritoExtractor extractor = new DistritoExtractor();

    @DisplayName("Reconoce distritos de Ica y devuelve el nombre oficial")
    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            busco un dentista en Parcona                 | Parcona
            Parcona                                      | Parcona
            PARCONA!!                                    | Parcona
            odontólogos en la tinguiña                   | La Tinguiña
            tinguiña                                     | La Tinguiña
            uno cerca de Los Aquijes                     | Los Aquijes
            en San Juan Bautista por favor               | San Juan Bautista
            estoy por Guadalupe                          | Salas
            dentista en Ica                              | Ica Centro
            Ica                                          | Ica Centro
            en el cercado de Ica                         | Ica Centro
            vivo en Santiago                             | Santiago
            Subtanjalla                                  | Subtanjalla
            pachacútec                                   | Pachacútec
            San José de los Molinos                      | San José de los Molinos
            """)
    void reconoceDistrito(String mensaje, String esperado) {
        assertThat(extractor.extraer(mensaje)).contains(esperado);
    }

    @DisplayName("No inventa distritos donde no los hay")
    @ParameterizedTest
    @ValueSource(strings = {
            "hola",
            "busco un dentista",
            "quiero agendar con el Dr. Santiago Ruiz",   // Santiago aquí es un nombre, no el distrito
            "las salas de espera son amplias",          // "salas" sin "en"/"de" delante
            ""
    })
    void sinDistrito(String mensaje) {
        assertThat(extractor.extraer(mensaje)).isEmpty();
    }

    @Test
    @DisplayName("Compara el texto libre del perfil con el distrito oficial")
    void comparaDistritoDelPerfil() {
        assertThat(extractor.coincide("la tinguina", "La Tinguiña")).isTrue();
        assertThat(extractor.coincide("LA TINGUIÑA", "La Tinguiña")).isTrue();
        assertThat(extractor.coincide("Ica", "Ica Centro")).isTrue();
        assertThat(extractor.coincide("Parcona", "La Tinguiña")).isFalse();
        assertThat(extractor.coincide(null, "Parcona")).isFalse();
    }
}

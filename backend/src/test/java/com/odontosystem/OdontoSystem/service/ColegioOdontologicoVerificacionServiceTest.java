package com.odontosystem.OdontoSystem.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColegioOdontologicoVerificacionServiceTest {

    @Test
    @DisplayName("El buscador del COP muestra el número con ceros a la izquierda: 1000 = 01000")
    void cerosALaIzquierda() {
        assertThat(ColegioOdontologicoVerificacionService.mismoNumero("01000", "1000")).isTrue();
        assertThat(ColegioOdontologicoVerificacionService.mismoNumero("12345", " 12345 ")).isTrue();
        assertThat(ColegioOdontologicoVerificacionService.mismoNumero("01000", "10000")).isFalse();
        assertThat(ColegioOdontologicoVerificacionService.mismoNumero("0", "0")).isTrue();
        assertThat(ColegioOdontologicoVerificacionService.mismoNumero("", "")).isFalse();
    }
}

package com.odontosystem.OdontoSystem.citas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.odontosystem.OdontoSystem.entity.ConfiguracionSistema;
import com.odontosystem.OdontoSystem.repository.ConfiguracionSistemaRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParametrosSistemaTest {

    private ConfiguracionSistemaRepository repository;
    private ParametrosSistema parametros;

    @BeforeEach
    void preparar() {
        repository = mock(ConfiguracionSistemaRepository.class);
        parametros = new ParametrosSistema(repository);
    }

    private void enBd(String clave, String valor) {
        when(repository.findById(clave)).thenReturn(Optional.of(new ConfiguracionSistema(clave, valor)));
    }

    @Test
    @DisplayName("Lee el número guardado en configuracion_sistema (con espacios)")
    void leeElValor() {
        enBd(ParametrosSistema.LIMITE_STRIKES, " 5 ");

        assertThat(parametros.entero(ParametrosSistema.LIMITE_STRIKES, 3)).isEqualTo(5);
    }

    @Test
    @DisplayName("Si la clave no existe o no es número, usa el valor por defecto")
    void valorPorDefecto() {
        when(repository.findById("NO_EXISTE")).thenReturn(Optional.empty());
        enBd("TEXTO", "doce");

        assertThat(parametros.entero("NO_EXISTE", 2)).isEqualTo(2);
        assertThat(parametros.entero("TEXTO", 12)).isEqualTo(12);
    }

    @Test
    @DisplayName("Caché Guava: varias lecturas de la misma clave consultan la BD una sola vez")
    void usaElCache() {
        enBd(ParametrosSistema.HORAS_CANCELACION_REEMBOLSO, "12");

        for (int i = 0; i < 10; i++) {
            assertThat(parametros.entero(ParametrosSistema.HORAS_CANCELACION_REEMBOLSO, 24)).isEqualTo(12);
        }
        verify(repository, times(1)).findById(ParametrosSistema.HORAS_CANCELACION_REEMBOLSO);
    }

    @Test
    @DisplayName("limpiarCache obliga a volver a leer el valor actualizado")
    void limpiarCache() {
        enBd(ParametrosSistema.LIMITE_STRIKES, "3");
        assertThat(parametros.entero(ParametrosSistema.LIMITE_STRIKES, 0)).isEqualTo(3);

        enBd(ParametrosSistema.LIMITE_STRIKES, "4");
        assertThat(parametros.entero(ParametrosSistema.LIMITE_STRIKES, 0)).isEqualTo(3);
        parametros.limpiarCache();
        assertThat(parametros.entero(ParametrosSistema.LIMITE_STRIKES, 0)).isEqualTo(4);
    }
}

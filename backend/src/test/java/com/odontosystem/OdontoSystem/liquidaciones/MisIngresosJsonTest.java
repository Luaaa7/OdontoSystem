package com.odontosystem.OdontoSystem.liquidaciones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.odontosystem.OdontoSystem.liquidaciones.LiquidacionService.MisIngresos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** El JSON de "Mis ingresos" con la misma regla snake_case que usa la API (spring.jackson.property-naming-strategy). */
class MisIngresosJsonTest {

    @Test
    @DisplayName("\"Devuelto a pacientes\" sale como devuelto_a_pacientes, el nombre que lee la web")
    void devueltoAPacientesConNombreCorrecto() throws Exception {
        ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        JsonNode nodo = json.readTree(json.writeValueAsString(new MisIngresos(new BigDecimal("19.00"),
                new BigDecimal("1140.00"), new BigDecimal("108.00"), List.of())));

        assertThat(nodo.has("devuelto_a_pacientes")).isTrue();
        assertThat(nodo.has("devuelto_apacientes")).isFalse();
        assertThat(nodo.get("devuelto_a_pacientes").decimalValue()).isEqualByComparingTo("108.00");
        assertThat(nodo.has("en_custodia")).isTrue();
    }
}

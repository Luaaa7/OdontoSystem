package com.odontosystem.OdontoSystem.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cupo mensual de consultas a APIs externas (como "tokens disponibles"). */
class ControlConsumoApiTest {

    private JdbcTemplate jdbc;
    private ControlConsumoApi consumo;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        // 1 de noviembre 03:00 UTC = 31 de octubre 22:00 en Lima: el periodo es el de Lima
        consumo = new ControlConsumoApi(jdbc, Clock.fixed(Instant.parse("2026-11-01T03:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("El periodo es el mes de Lima (AAAA-MM)")
    void periodo() {
        assertThat(consumo.periodo()).isEqualTo("2026-10");
    }

    @Test
    @DisplayName("Reserva: true si la BD devolvió la fila (quedaba cupo); false si no (límite alcanzado)")
    void reservar() {
        when(jdbc.queryForList(anyString(), eq(Integer.class), eq("FACTILIZA_DNI"), eq("2026-10"), eq(100)))
                .thenReturn(List.of(5))
                .thenReturn(List.of());

        assertThat(consumo.reservar("FACTILIZA_DNI", 100)).isTrue();
        assertThat(consumo.reservar("FACTILIZA_DNI", 100)).isFalse();
    }

    @Test
    @DisplayName("Límite 0: nunca reserva ni toca la BD")
    void limiteCero() {
        assertThat(consumo.reservar("FACTILIZA_DNI", 0)).isFalse();
        verify(jdbc, never()).queryForList(anyString(), eq(Integer.class), org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    @DisplayName("Liberar devuelve 1 al cupo del mes; usadas lee el contador (0 si no hay fila)")
    void liberarYUsadas() {
        consumo.liberar("FACTILIZA_DNI");
        verify(jdbc).update(anyString(), eq("FACTILIZA_DNI"), eq("2026-10"));

        when(jdbc.queryForList(anyString(), eq(Integer.class), eq("FACTILIZA_DNI"), eq("2026-10")))
                .thenReturn(List.of(12)).thenReturn(List.of());
        assertThat(consumo.usadas("FACTILIZA_DNI")).isEqualTo(12);
        assertThat(consumo.usadas("FACTILIZA_DNI")).isZero();
    }
}

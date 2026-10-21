package com.odontosystem.OdontoSystem.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * Cupo mensual de consultas a APIs externas que cobran por consulta (Factiliza). Funciona como los
 * "tokens disponibles" de una API Key: antes de cada consulta se reserva 1; si ya no queda cupo, no se consulta.
 * La reserva es atómica en la BD (un solo INSERT … ON CONFLICT … WHERE usadas &lt; límite), así dos peticiones a la
 * vez no pueden pasarse del límite. Si la API externa no respondió, la consulta se devuelve al cupo.
 */
@Service
public class ControlConsumoApi {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private static final String RESERVAR = """
            INSERT INTO consumo_api_externa (servicio, periodo, consultas_usadas, actualizado_en)
            VALUES (?, ?, 1, now())
            ON CONFLICT (servicio, periodo) DO UPDATE
               SET consultas_usadas = consumo_api_externa.consultas_usadas + 1, actualizado_en = now()
             WHERE consumo_api_externa.consultas_usadas < ?
            RETURNING consultas_usadas
            """;
    private static final String LIBERAR = """
            UPDATE consumo_api_externa
               SET consultas_usadas = GREATEST(consultas_usadas - 1, 0), actualizado_en = now()
             WHERE servicio = ? AND periodo = ?
            """;
    private static final String USADAS = """
            SELECT consultas_usadas FROM consumo_api_externa WHERE servicio = ? AND periodo = ?
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ControlConsumoApi(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Reserva 1 consulta del mes. false si ya se llegó al límite (no hay que llamar a la API). */
    public boolean reservar(String servicio, int limiteMensual) {
        if (limiteMensual <= 0) {
            return false;
        }
        List<Integer> usadas = jdbc.queryForList(RESERVAR, Integer.class, servicio, periodo(), limiteMensual);
        return !usadas.isEmpty();
    }

    /** Devuelve al cupo una consulta reservada que no llegó a hacerse (la API no respondió). */
    public void liberar(String servicio) {
        jdbc.update(LIBERAR, servicio, periodo());
    }

    /** Consultas usadas este mes. */
    public int usadas(String servicio) {
        List<Integer> filas = jdbc.queryForList(USADAS, Integer.class, servicio, periodo());
        return filas.isEmpty() || filas.get(0) == null ? 0 : filas.get(0);
    }

    String periodo() {
        return YearMonth.now(clock.withZone(LIMA)).toString();
    }
}

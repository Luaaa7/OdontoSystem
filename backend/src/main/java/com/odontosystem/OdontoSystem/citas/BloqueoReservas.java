package com.odontosystem.OdontoSystem.citas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Candado en Redis para que dos pacientes no reserven el mismo turno al mismo tiempo.
 * Es la primera barrera (rápida); la segunda es la restricción ex_citas_sin_solape de PostgreSQL.
 * Si Redis no responde, la reserva sigue y la BD hace de única barrera.
 */
@Component
public class BloqueoReservas {

    private static final Logger log = LoggerFactory.getLogger(BloqueoReservas.class);
    static final Duration DURACION = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;

    public BloqueoReservas(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String clave(UUID odontologoId, OffsetDateTime inicio) {
        return "odontosystem:reserva:" + odontologoId + ":" + inicio.toEpochSecond();
    }

    /** true si se obtuvo el candado (o si Redis no está disponible); false si otro lo tiene. */
    public boolean tomar(String clave, UUID pacienteId) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(clave, pacienteId.toString(), DURACION);
            return !Boolean.FALSE.equals(ok);
        } catch (RuntimeException e) {
            log.warn("Redis no disponible al reservar {}; se confía en la restricción de la BD", clave, e);
            return true;
        }
    }

    public void soltar(String clave) {
        try {
            redis.delete(clave);
        } catch (RuntimeException e) {
            log.warn("No se pudo liberar {} en Redis (vence solo en {} s)", clave, DURACION.toSeconds());
        }
    }
}

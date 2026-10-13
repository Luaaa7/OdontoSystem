package com.odontosystem.OdontoSystem.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Al arrancar, prueba la conexión a Redis y lo deja escrito en el log.
 * No detiene la app: sin Redis, la reserva de turnos se apoya solo en la BD y el login funciona sin límite de intentos.
 */
@Component
public class ComprobacionRedis implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ComprobacionRedis.class);
    private final StringRedisTemplate redis;

    public ComprobacionRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            redis.opsForValue().set("odontosystem:ping", "ok", java.time.Duration.ofMinutes(1));
            log.info("Redis conectado: candado de reservas y límite de intentos de login activos");
        } catch (RuntimeException e) {
            log.warn("Redis NO disponible ({}): la app sigue, pero sin candado de reservas ni límite de intentos de login",
                    e.getMessage());
        }
    }
}

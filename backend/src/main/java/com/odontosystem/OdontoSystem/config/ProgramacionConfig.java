package com.odontosystem.OdontoSystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** Activa las tareas automáticas (@Scheduled): vencer reservas no pagadas, recordatorios, etc. */
@Configuration
@EnableScheduling
public class ProgramacionConfig {

    /** Reloj inyectable: en producción el del sistema; en las pruebas uno fijo. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}

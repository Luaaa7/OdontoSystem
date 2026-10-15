package com.odontosystem.OdontoSystem.citas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cada día a las 6:30 a. m. (Lima): pregunta "¿atiendes hoy?" a los odontólogos con citas que no confirmaron. */
@Component
public class TareasAsistencia {

    private static final Logger log = LoggerFactory.getLogger(TareasAsistencia.class);

    private final AgendaDiariaService agendaDiaria;
    private final AvisosAsistencia avisos;

    public TareasAsistencia(AgendaDiariaService agendaDiaria, AvisosAsistencia avisos) {
        this.agendaDiaria = agendaDiaria;
        this.avisos = avisos;
    }

    @Scheduled(cron = "0 30 6 * * *", zone = "America/Lima")
    public void preguntarAgendaDelDia() {
        try {
            long enviados = agendaDiaria.pendientesDeConfirmar().stream().filter(avisos::agendaDelDia).count();
            if (enviados > 0) {
                log.info("Se preguntó \"¿atiendes hoy?\" a {} odontólogos", enviados);
            }
        } catch (RuntimeException e) {
            log.error("Falló el aviso diario de agenda", e);
        }
    }
}

package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.AgendaDiariaService.AvisoAgenda;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.ReporteAusencia;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.notificaciones.EnviadorCorreo;
import com.odontosystem.OdontoSystem.notificaciones.PlantillasCorreo;
import com.odontosystem.OdontoSystem.repository.ReporteAusenciaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Correos al odontólogo: "¿atiendes hoy?" (cada mañana) y "un paciente reportó que no lo atendiste". */
@Component
public class AvisosAsistencia {

    private static final Logger log = LoggerFactory.getLogger(AvisosAsistencia.class);
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter FECHA =
            DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'a las' HH:mm", Locale.of("es", "PE"));
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private final ReporteAusenciaRepository reportes;
    private final EnviadorCorreo enviador;
    private final PlantillasCorreo plantillas;

    public AvisosAsistencia(ReporteAusenciaRepository reportes, EnviadorCorreo enviador, PlantillasCorreo plantillas) {
        this.reportes = reportes;
        this.enviador = enviador;
        this.plantillas = plantillas;
    }

    /** Correo de la mañana; devuelve true si salió. */
    public boolean agendaDelDia(AvisoAgenda a) {
        if (!enviador.activo()) return false;
        try {
            Map<String, Object> v = new HashMap<>();
            v.put("caso", "agenda");
            v.put("nombre", a.nombre());
            v.put("citas", a.citas());
            v.put("hora", HORA.format(a.primeraCita().atZoneSameInstant(LIMA)));
            enviador.enviar(a.correo(), a.nombre(), "¿Atiendes hoy? Tienes " + a.citas() + " cita(s)",
                    plantillas.armar("asistencia", v));
            return true;
        } catch (RuntimeException e) {
            log.warn("No se pudo enviar el aviso de agenda a {}: {}", a.correo(), e.getMessage());
            return false;
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alReportar(ReporteAusenciaCreado evento) {
        if (!enviador.activo()) return;
        try {
            ReporteAusencia r = reportes.conDetalle(evento.reporteId()).orElse(null);
            if (r == null) return;
            Cita c = r.getCita();
            Usuario odontologo = c.getOdontologo().getUsuario();
            if (odontologo.getCorreo() == null || odontologo.getCorreo().isBlank()) return;
            Map<String, Object> v = new HashMap<>();
            v.put("caso", "reporte");
            v.put("nombre", odontologo.getNombreCompleto());
            v.put("paciente", c.getPaciente().getNombreCompleto());
            v.put("servicio", c.getServicio().getTitulo());
            v.put("fecha", FECHA.format(c.getInicioProgramado().atZoneSameInstant(LIMA)));
            v.put("limite", FECHA.format(r.getCreadoEn().plus(AsistenciaService.PLAZO_RESPUESTA).atZoneSameInstant(LIMA)));
            enviador.enviar(odontologo.getCorreo(), odontologo.getNombreCompleto(),
                    "Un paciente reportó que no lo atendiste", plantillas.armar("asistencia", v));
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar el reporte de ausencia {}: {}", evento.reporteId(), e.getMessage());
        }
    }
}

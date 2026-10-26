package com.odontosystem.OdontoSystem.reclamos;

import com.odontosystem.OdontoSystem.entity.Reclamo;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.notificaciones.EnviadorCorreo;
import com.odontosystem.OdontoSystem.notificaciones.PlantillasCorreo;
import com.odontosystem.OdontoSystem.reclamos.ReclamoDtos.ReclamoResponse;
import com.odontosystem.OdontoSystem.repository.ReclamoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Correos del libro de reclamaciones: constancia al registrar (y aviso al odontólogo que debe atenderlo)
 * y la respuesta al reclamante. Corre después del commit y en otro hilo; si el correo falla, el reclamo
 * igual queda registrado y se puede consultar en la app.
 */
@Component
public class AvisosReclamos {

    private static final Logger log = LoggerFactory.getLogger(AvisosReclamos.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", Locale.of("es", "PE"));

    private final ReclamoRepository reclamos;
    private final EnviadorCorreo enviador;
    private final PlantillasCorreo plantillas;

    public AvisosReclamos(ReclamoRepository reclamos, EnviadorCorreo enviador, PlantillasCorreo plantillas) {
        this.reclamos = reclamos;
        this.enviador = enviador;
        this.plantillas = plantillas;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alRegistrar(ReclamoRegistrado evento) {
        if (!enviador.activo()) return;
        try {
            Reclamo r = reclamos.conDetalle(evento.reclamoId()).orElse(null);
            if (r == null) return;
            ReclamoResponse d = ReclamoService.aRespuesta(r);
            Usuario reclamante = r.getUsuario();
            switch (evento.aviso()) {
                case RECIBIDO -> {
                    enviar(reclamante, "Recibimos tu " + etiqueta(d) + " " + d.codigo(), "recibido", d, reclamante.getNombreCompleto());
                    if (ReclamoService.atiendeOdontologo(r) && r.getCita().getOdontologo().getUsuario() != null) {
                        Usuario odontologo = r.getCita().getOdontologo().getUsuario();
                        enviar(odontologo, "Un paciente registró un " + etiqueta(d) + " " + d.codigo(), "para-odontologo",
                                d, odontologo.getNombreCompleto());
                    }
                }
                case RESPONDIDO -> enviar(reclamante, "Respuesta a tu " + etiqueta(d) + " " + d.codigo(), "respondido",
                        d, reclamante.getNombreCompleto());
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo enviar el aviso del reclamo {} ({}): {}", evento.reclamoId(), evento.aviso(), e.getMessage());
        }
    }

    private void enviar(Usuario para, String asunto, String caso, ReclamoResponse d, String nombre) {
        if (para.getCorreo() == null || para.getCorreo().isBlank()) return;
        Map<String, Object> v = new HashMap<>();
        v.put("caso", caso);
        v.put("nombre", nombre);
        v.put("codigo", d.codigo());
        v.put("tipo", etiqueta(d));
        v.put("asunto", d.asunto());
        v.put("descripcion", d.descripcion());
        v.put("respuesta", d.respuesta());
        v.put("estado", d.estado().name());
        v.put("reclamante", d.reclamante());
        v.put("servicio", d.servicio());
        v.put("atiendeOdontologo", "ODONTOLOGO".equals(d.atiende()));
        v.put("odontologo", d.odontologo());
        v.put("responderAntes", FECHA.format(d.responderAntes()));
        enviador.enviar(para.getCorreo(), nombre, asunto, plantillas.armar("reclamo", v));
    }

    private static String etiqueta(ReclamoResponse d) {
        return switch (d.tipo()) {
            case CONSULTA -> "consulta";
            case INCIDENCIA -> "incidencia";
            case RECLAMO -> "reclamo";
        };
    }
}

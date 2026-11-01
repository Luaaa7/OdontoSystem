package com.odontosystem.OdontoSystem.notificaciones;

import com.odontosystem.OdontoSystem.entity.NotificacionApp;
import com.odontosystem.OdontoSystem.repository.NotificacionAppRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Bandeja de avisos dentro de la app (la campanita). Cada aviso que sale por correo o push también se guarda aquí
 * con su texto y la fecha en que ocurrió, para que el usuario lo vea y lo marque como leído desde cualquier
 * dispositivo.
 */
@Service
public class BandejaNotificaciones {

    private static final Logger log = LoggerFactory.getLogger(BandejaNotificaciones.class);
    /** Cuántas se devuelven como máximo (las más recientes). */
    static final int MAXIMO = 50;
    static final int LARGO_TITULO = 150;
    static final int LARGO_MENSAJE = 300;

    public record NotificacionResponse(UUID id, String tipo, String titulo, String mensaje, UUID citaId,
                                       OffsetDateTime creadoEn, boolean leida) {}

    public record BandejaResponse(long noLeidas, List<NotificacionResponse> notificaciones) {}

    public record LeidasResponse(int marcadas) {}

    private final NotificacionAppRepository repositorio;
    private final Clock clock;

    public BandejaNotificaciones(NotificacionAppRepository repositorio, Clock clock) {
        this.repositorio = repositorio;
        this.clock = clock;
    }

    /**
     * Guarda un aviso en la bandeja del usuario. Nunca lanza: si falla, el correo y el push siguen igual.
     * Sin @Transactional a propósito: save() abre su propia transacción y un error no contamina la de quien llama.
     */
    public void registrar(UUID usuarioId, String tipo, String titulo, String mensaje, UUID citaId) {
        if (usuarioId == null || titulo == null || titulo.isBlank()) {
            return;
        }
        try {
            repositorio.save(NotificacionApp.builder()
                    .usuarioId(usuarioId)
                    .tipo(tipo == null ? "AVISO" : tipo)
                    .titulo(recortar(titulo, LARGO_TITULO))
                    .mensaje(recortar(mensaje == null || mensaje.isBlank() ? titulo : mensaje, LARGO_MENSAJE))
                    .citaId(citaId)
                    .creadoEn(OffsetDateTime.now(clock))
                    .build());
        } catch (RuntimeException e) {
            log.warn("No se pudo guardar el aviso \"{}\" en la bandeja: {}", titulo, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public BandejaResponse deUsuario(UUID usuarioId) {
        List<NotificacionResponse> lista = repositorio.recientes(usuarioId, PageRequest.of(0, MAXIMO)).stream()
                .map(BandejaNotificaciones::aRespuesta).toList();
        return new BandejaResponse(repositorio.countByUsuarioIdAndLeidaEnIsNull(usuarioId), lista);
    }

    /** Marca una como leída. 404 si no existe o es de otro usuario. */
    @Transactional
    public NotificacionResponse marcarLeida(UUID usuarioId, UUID notificacionId) {
        NotificacionApp n = repositorio.findByIdAndUsuarioId(notificacionId, usuarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificación no encontrada"));
        if (n.getLeidaEn() == null) {
            n.setLeidaEn(OffsetDateTime.now(clock));
            repositorio.save(n);
        }
        return aRespuesta(n);
    }

    @Transactional
    public LeidasResponse marcarTodasLeidas(UUID usuarioId) {
        return new LeidasResponse(repositorio.marcarTodasLeidas(usuarioId, OffsetDateTime.now(clock)));
    }

    // ------------------------------------------------------------------

    static NotificacionResponse aRespuesta(NotificacionApp n) {
        return new NotificacionResponse(n.getId(), n.getTipo(), n.getTitulo(), n.getMensaje(), n.getCitaId(),
                n.getCreadoEn(), n.getLeidaEn() != null);
    }

    static String recortar(String texto, int maximo) {
        String limpio = texto.strip();
        return limpio.length() <= maximo ? limpio : limpio.substring(0, maximo - 1) + "…";
    }
}

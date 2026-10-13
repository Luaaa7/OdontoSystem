package com.odontosystem.OdontoSystem.reclamos;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoReclamo;
import com.odontosystem.OdontoSystem.entity.Reclamo;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.reclamos.ReclamoDtos.ReclamoRequest;
import com.odontosystem.OdontoSystem.reclamos.ReclamoDtos.ReclamoResponse;
import com.odontosystem.OdontoSystem.reclamos.ReclamoDtos.RespuestaRequest;
import com.odontosystem.OdontoSystem.reclamos.ReclamoRegistrado.Aviso;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.ReclamoRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Libro de reclamaciones virtual (arquitectura sin administrador).
 * <ul>
 *   <li>Cualquier usuario registra una CONSULTA, INCIDENCIA o RECLAMO, opcionalmente sobre una cita suya.</li>
 *   <li>Si un paciente reclama sobre una cita, lo atiende el odontólogo de esa cita (responde y lo marca resuelto).</li>
 *   <li>El resto (problemas de la plataforma, reclamos de odontólogos) queda para el equipo de soporte.</li>
 *   <li>El usuario recibe una constancia por correo con su código y la fecha límite de respuesta
 *       (15 días hábiles), y puede cerrar su reclamo cuando quiera.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReclamoService {

    /** Para que nadie llene la bandeja de un odontólogo: como máximo 5 reclamos sin resolver por usuario. */
    static final int MAX_SIN_RESOLVER = 5;
    static final int DIAS_HABILES_RESPUESTA = 15;
    static final ZoneId ZONA = ZoneId.of("America/Lima");
    private static final Set<EstadoReclamo> SIN_RESOLVER = Set.of(EstadoReclamo.ABIERTO, EstadoReclamo.EN_PROCESO);

    private final ReclamoRepository reclamoRepository;
    private final CitaRepository citaRepository;
    private final UsuarioRepository usuarioRepository;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    @Transactional
    public ReclamoResponse registrar(UUID usuarioId, ReclamoRequest req) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        if (reclamoRepository.countByUsuario_IdAndEstadoIn(usuarioId, SIN_RESOLVER) >= MAX_SIN_RESOLVER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya tienes " + MAX_SIN_RESOLVER
                    + " reclamos sin resolver; espera su respuesta o cierra alguno antes de registrar otro");
        }
        Cita cita = null;
        if (req.citaId() != null) {
            cita = citaRepository.findById(req.citaId())
                    .filter(c -> c.getPaciente().getId().equals(usuarioId)
                            || c.getOdontologo().getUsuarioId().equals(usuarioId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cita no encontrada"));
        }
        Reclamo reclamo = reclamoRepository.saveAndFlush(Reclamo.builder()
                .usuario(usuario)
                .cita(cita)
                .tipo(req.tipo())
                .asunto(req.asunto().strip())
                .descripcion(req.descripcion().strip())
                .creadoEn(OffsetDateTime.now(clock))
                .build());
        eventos.publishEvent(new ReclamoRegistrado(reclamo.getId(), Aviso.RECIBIDO));
        return aRespuesta(reclamo);
    }

    @Transactional(readOnly = true)
    public List<ReclamoResponse> mios(UUID usuarioId) {
        return reclamoRepository.delUsuario(usuarioId).stream().map(ReclamoService::aRespuesta).toList();
    }

    /** Lo ve quien lo registró o el odontólogo que debe atenderlo. */
    @Transactional(readOnly = true)
    public ReclamoResponse detalle(UUID usuarioId, UUID reclamoId) {
        return reclamoRepository.conDetalle(reclamoId)
                .filter(r -> r.getUsuario().getId().equals(usuarioId) || loAtiende(r, usuarioId))
                .map(ReclamoService::aRespuesta)
                .orElseThrow(ReclamoService::noEncontrado);
    }

    /** El usuario da por terminado su reclamo (conforme con la respuesta o ya no lo necesita). */
    @Transactional
    public ReclamoResponse cerrar(UUID usuarioId, UUID reclamoId) {
        Reclamo r = reclamoRepository.conDetalle(reclamoId)
                .filter(x -> x.getUsuario().getId().equals(usuarioId))
                .orElseThrow(ReclamoService::noEncontrado);
        if (r.getEstado() == EstadoReclamo.CERRADO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este reclamo ya está cerrado");
        }
        r.setEstado(EstadoReclamo.CERRADO);
        if (r.getResueltoEn() == null) r.setResueltoEn(OffsetDateTime.now(clock));
        return aRespuesta(reclamoRepository.saveAndFlush(r));
    }

    /** Bandeja del odontólogo: reclamos de pacientes sobre sus citas, los más antiguos primero. */
    @Transactional(readOnly = true)
    public List<ReclamoResponse> paraOdontologo(UUID odontologoId, EstadoReclamo estado) {
        return reclamoRepository.paraOdontologo(odontologoId).stream()
                .filter(r -> estado == null || r.getEstado() == estado)
                .map(ReclamoService::aRespuesta)
                .toList();
    }

    @Transactional
    public ReclamoResponse responder(UUID odontologoId, UUID reclamoId, RespuestaRequest req) {
        Reclamo r = reclamoRepository.conDetalle(reclamoId)
                .filter(x -> loAtiende(x, odontologoId))
                .orElseThrow(ReclamoService::noEncontrado);
        if (!SIN_RESOLVER.contains(r.getEstado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este reclamo ya está "
                    + r.getEstado().name().toLowerCase(Locale.ROOT) + "; no se puede volver a responder");
        }
        r.setRespuesta(req.respuesta().strip());
        if (req.resuelto()) {
            r.setEstado(EstadoReclamo.RESUELTO);
            r.setResueltoEn(OffsetDateTime.now(clock));
        } else {
            r.setEstado(EstadoReclamo.EN_PROCESO);
        }
        Reclamo guardado = reclamoRepository.saveAndFlush(r);
        eventos.publishEvent(new ReclamoRegistrado(guardado.getId(), Aviso.RESPONDIDO));
        return aRespuesta(guardado);
    }

    // ------------------------------------------------------------------

    /** El odontólogo atiende el reclamo si lo hizo el paciente de una cita suya. */
    static boolean loAtiende(Reclamo r, UUID odontologoId) {
        return atiendeOdontologo(r) && r.getCita().getOdontologo().getUsuarioId().equals(odontologoId);
    }

    static boolean atiendeOdontologo(Reclamo r) {
        Cita c = r.getCita();
        return c != null && c.getPaciente() != null && c.getPaciente().getId().equals(r.getUsuario().getId());
    }

    /** Código corto para seguimiento: REC- y los primeros 8 caracteres del id. */
    public static String codigo(UUID id) {
        return "REC-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** Suma días hábiles (lunes a viernes; no descuenta feriados). */
    static LocalDate sumarDiasHabiles(LocalDate desde, int dias) {
        LocalDate fecha = desde;
        int contados = 0;
        while (contados < dias) {
            fecha = fecha.plusDays(1);
            if (fecha.getDayOfWeek() != DayOfWeek.SATURDAY && fecha.getDayOfWeek() != DayOfWeek.SUNDAY) {
                contados++;
            }
        }
        return fecha;
    }

    static LocalDate responderAntes(Reclamo r) {
        return sumarDiasHabiles(r.getCreadoEn().atZoneSameInstant(ZONA).toLocalDate(), DIAS_HABILES_RESPUESTA);
    }

    static ReclamoResponse aRespuesta(Reclamo r) {
        Cita c = r.getCita();
        String odontologo = c != null && c.getOdontologo().getUsuario() != null
                ? c.getOdontologo().getUsuario().getNombreCompleto() : null;
        return new ReclamoResponse(r.getId(), codigo(r.getId()), r.getTipo(), r.getEstado(), r.getAsunto(),
                r.getDescripcion(), r.getUsuario().getNombreCompleto(), c != null ? c.getId() : null, odontologo,
                c != null && c.getServicio() != null ? c.getServicio().getTitulo() : null,
                c != null ? c.getInicioProgramado() : null, atiendeOdontologo(r) ? "ODONTOLOGO" : "SOPORTE",
                r.getRespuesta(), r.getCreadoEn(), r.getActualizadoEn(), r.getResueltoEn(), responderAntes(r));
    }

    private static ResponseStatusException noEncontrado() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Reclamo no encontrado");
    }
}

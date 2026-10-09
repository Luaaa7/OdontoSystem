package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.CitaDtos.CitaResponse;
import com.odontosystem.OdontoSystem.citas.CitaDtos.OdontologoResumen;
import com.odontosystem.OdontoSystem.citas.CitaDtos.PacienteResumen;
import com.odontosystem.OdontoSystem.citas.CitaDtos.ReservaRequest;
import com.odontosystem.OdontoSystem.citas.CitaDtos.ServicioResumen;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoReputacion;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.ReputacionPaciente;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.ReputacionPacienteRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reservas y ciclo de vida de una cita (RF03, RF05, RF09, RF11).
 * <pre>
 * PENDIENTE_PAGO ──pago──► CONFIRMADA (o PENDIENTE_CONFIRMACION si el odontólogo aprueba a mano)
 *       │                       │
 *       └─ no paga en N min ─► CANCELADA_SIN_REEMBOLSO
 * CONFIRMADA ──► COMPLETADA | INASISTENCIA_PENALIZADA | CANCELADA_CON/SIN_REEMBOLSO
 * </pre>
 * Cada cambio de estado lo registra la BD en bitacora_auditoria_citas, con el usuario que lo hizo.
 */
@Service
@RequiredArgsConstructor
public class CitaService {

    /** Cuánto tiempo tiene el paciente para pagar el depósito antes de que se libere el turno. */
    static final Duration PLAZO_PAGO = Duration.ofMinutes(15);
    static final int MAXIMO_PENDIENTES_DE_PAGO = 3;
    private static final Set<EstadoCita> CANCELABLES =
            Set.of(EstadoCita.PENDIENTE_PAGO, EstadoCita.PENDIENTE_CONFIRMACION, EstadoCita.CONFIRMADA);

    private final CitaRepository citaRepository;
    private final UsuarioRepository usuarioRepository;
    private final ReputacionPacienteRepository reputacionRepository;
    private final DisponibilidadService disponibilidadService;
    private final BloqueoReservas bloqueoReservas;
    private final ParametrosSistema parametros;
    private final EntityManager entityManager;
    private final Clock clock;

    // ------------------------------------------------------------------
    // Reservar
    // ------------------------------------------------------------------

    @Transactional
    public CitaResponse reservar(UUID pacienteId, ReservaRequest req) {
        Usuario paciente = usuarioRepository.findById(pacienteId)
                .filter(u -> u.getRol() == RolUsuario.PACIENTE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo un paciente puede reservar"));
        exigirReputacionVigente(pacienteId);
        if (citaRepository.countByPaciente_IdAndEstado(pacienteId, EstadoCita.PENDIENTE_PAGO) >= MAXIMO_PENDIENTES_DE_PAGO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tienes " + MAXIMO_PENDIENTES_DE_PAGO + " reservas sin pagar; págalas o cancélalas antes de reservar otra");
        }

        PerfilOdontologo perfil = disponibilidadService.exigirPerfilPublicable(req.odontologoId());
        Servicio servicio = disponibilidadService.exigirServicioActivo(req.odontologoId(), req.servicioId());
        ZoneId zona = ZoneId.of(perfil.getZonaHoraria());
        OffsetDateTime inicio = req.inicio().atZoneSameInstant(zona).toOffsetDateTime();

        boolean turnoLibre = disponibilidadService.turnosLibres(perfil, servicio, inicio.toLocalDate()).stream()
                .anyMatch(t -> t.inicio().isEqual(inicio));
        if (!turnoLibre) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese horario ya no está disponible; elige otro");
        }

        String candado = BloqueoReservas.clave(perfil.getUsuarioId(), inicio);
        if (!bloqueoReservas.tomar(candado, pacienteId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Otro paciente está reservando ese horario; elige otro");
        }
        try {
            fijarActor(pacienteId);
            Cita cita = Cita.builder()
                    .paciente(paciente)
                    .odontologo(perfil)
                    .servicio(servicio)
                    .inicioProgramado(inicio)
                    .finProgramado(inicio.plusMinutes(servicio.getDuracionMinutos()))
                    // Los triggers sobrescriben estos 4 valores con la copia del servicio; se envían porque son NOT NULL
                    .zonaHoraria(perfil.getZonaHoraria())
                    .precioServicio(servicio.getPrecioTotal())
                    .montoDepositoRequerido(servicio.getMontoDeposito())
                    .duracionMinutos(servicio.getDuracionMinutos())
                    .estado(EstadoCita.PENDIENTE_PAGO)
                    .referenciaBloqueo(candado)
                    .build();
            Cita guardada = citaRepository.saveAndFlush(cita);
            entityManager.refresh(guardada);
            return aRespuesta(guardada);
        } catch (DataIntegrityViolationException e) {
            // ex_citas_sin_solape: otra reserva ganó el turno entre la comprobación y el insert
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese horario acaba de ser reservado; elige otro");
        } finally {
            bloqueoReservas.soltar(candado);
        }
    }

    private void exigirReputacionVigente(UUID pacienteId) {
        reputacionRepository.findById(pacienteId).ifPresent(r -> {
            boolean restringido = r.getRestringidoHasta() != null && r.getRestringidoHasta().isAfter(OffsetDateTime.now(clock));
            if (r.getEstado() == EstadoReputacion.BLOQUEADO || restringido) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Tu cuenta no puede reservar por inasistencias anteriores");
            }
        });
    }

    // ------------------------------------------------------------------
    // Consultar
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CitaResponse> misCitas(UUID pacienteId) {
        return citaRepository.delPaciente(pacienteId).stream().map(this::aRespuesta).toList();
    }

    @Transactional(readOnly = true)
    public List<CitaResponse> agenda(UUID odontologoId, OffsetDateTime desde, OffsetDateTime hasta) {
        if (!hasta.isAfter(desde) || Duration.between(desde, hasta).toDays() > 62) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El rango de la agenda debe ser de 1 a 62 días");
        }
        return citaRepository.agenda(odontologoId, desde, hasta).stream().map(this::aRespuesta).toList();
    }

    /** Solo la ven el paciente y el odontólogo de la cita; para cualquier otro, 404. */
    @Transactional(readOnly = true)
    public CitaResponse detalle(UUID usuarioId, UUID citaId) {
        return aRespuesta(exigirParticipante(usuarioId, citaId));
    }

    // ------------------------------------------------------------------
    // Transiciones
    // ------------------------------------------------------------------

    /**
     * Cancela la cita. Reembolso: si la cancela el odontólogo, siempre; si la cancela el paciente,
     * solo con al menos HORAS_MINIMAS_CANCELACION_REEMBOLSO (12 h) de anticipación. Sin pago no hay reembolso.
     */
    @Transactional
    public CitaResponse cancelar(UUID usuarioId, UUID citaId, String motivo) {
        Cita cita = exigirParticipante(usuarioId, citaId);
        if (!CANCELABLES.contains(cita.getEstado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Una cita " + cita.getEstado() + " ya no se puede cancelar");
        }
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        if (!cita.getInicioProgramado().isAfter(ahora)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cita ya empezó; no se puede cancelar");
        }
        boolean esOdontologo = cita.getOdontologo().getUsuarioId().equals(usuarioId);
        boolean pagada = cita.getEstado() != EstadoCita.PENDIENTE_PAGO;
        int horasMinimas = parametros.entero(ParametrosSistema.HORAS_CANCELACION_REEMBOLSO, 12);
        boolean conAnticipacion = Duration.between(ahora, cita.getInicioProgramado()).toMinutes() >= horasMinimas * 60L;

        EstadoCita nuevo = pagada && (esOdontologo || conAnticipacion)
                ? EstadoCita.CANCELADA_CON_REEMBOLSO
                : EstadoCita.CANCELADA_SIN_REEMBOLSO;
        String texto = motivo == null || motivo.isBlank()
                ? (esOdontologo ? "Cancelada por el odontólogo" : "Cancelada por el paciente")
                : motivo.trim();
        return cambiarEstado(cita, usuarioId, nuevo, texto);
    }

    /** RF11: el odontólogo aprueba una reserva pagada que esperaba su confirmación. */
    @Transactional
    public CitaResponse confirmar(UUID odontologoId, UUID citaId) {
        Cita cita = exigirDelOdontologo(odontologoId, citaId);
        exigirEstado(cita, EstadoCita.PENDIENTE_CONFIRMACION);
        try {
            return cambiarEstado(cita, odontologoId, EstadoCita.CONFIRMADA, null);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese horario ya está ocupado por otra cita confirmada");
        }
    }

    @Transactional
    public CitaResponse completar(UUID odontologoId, UUID citaId) {
        Cita cita = exigirDelOdontologo(odontologoId, citaId);
        exigirEstado(cita, EstadoCita.CONFIRMADA);
        if (cita.getInicioProgramado().isAfter(OffsetDateTime.now(clock))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cita todavía no empieza");
        }
        cita.setCompletadoEn(OffsetDateTime.now(clock));
        return cambiarEstado(cita, odontologoId, EstadoCita.COMPLETADA, null);
    }

    /**
     * RF09: el paciente no llegó. Solo después de la tolerancia (15 min, la fija la BD).
     * Suma un strike; con LIMITE_MAXIMO_STRIKES (3) la cuenta queda BLOQUEADA para reservar.
     */
    @Transactional
    public CitaResponse marcarInasistencia(UUID odontologoId, UUID citaId) {
        Cita cita = exigirDelOdontologo(odontologoId, citaId);
        exigirEstado(cita, EstadoCita.CONFIRMADA);
        OffsetDateTime vence = cita.getToleranciaVenceEn() != null ? cita.getToleranciaVenceEn()
                : cita.getInicioProgramado().plusMinutes(15);
        if (OffsetDateTime.now(clock).isBefore(vence)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Aún está dentro del tiempo de tolerancia");
        }
        UUID pacienteId = cita.getPaciente().getId();
        ReputacionPaciente reputacion = reputacionRepository.findById(pacienteId)
                .orElseGet(() -> ReputacionPaciente.builder().usuario(cita.getPaciente()).build());
        short strikes = (short) (reputacion.getCantidadStrikes() + 1);
        int limite = parametros.entero(ParametrosSistema.LIMITE_STRIKES, 3);
        reputacion.setCantidadStrikes(strikes);
        reputacion.setEstado(strikes >= limite ? EstadoReputacion.BLOQUEADO : EstadoReputacion.REGULAR);
        reputacion.setActualizadoEn(OffsetDateTime.now(clock));
        reputacionRepository.save(reputacion);
        return cambiarEstado(cita, odontologoId, EstadoCita.INASISTENCIA_PENALIZADA, "Inasistencia del paciente");
    }

    /** Lo usa la tarea programada: libera los turnos de reservas que no se pagaron a tiempo. */
    @Transactional
    public int vencerReservasSinPago() {
        OffsetDateTime limite = OffsetDateTime.now(clock).minus(PLAZO_PAGO);
        List<Cita> vencidas = citaRepository.findByEstadoAndCreadoEnBefore(EstadoCita.PENDIENTE_PAGO, limite);
        for (Cita cita : vencidas) {
            cita.setEstado(EstadoCita.CANCELADA_SIN_REEMBOLSO);
            cita.setCanceladoEn(OffsetDateTime.now(clock));
            cita.setMotivoCancelacion("No se pagó el depósito a tiempo");
        }
        citaRepository.saveAll(vencidas);
        return vencidas.size();
    }

    // ------------------------------------------------------------------

    CitaResponse cambiarEstado(Cita cita, UUID actorId, EstadoCita nuevo, String motivo) {
        fijarActor(actorId);
        cita.setEstado(nuevo);
        if (nuevo == EstadoCita.CANCELADA_CON_REEMBOLSO || nuevo == EstadoCita.CANCELADA_SIN_REEMBOLSO) {
            cita.setCanceladoEn(OffsetDateTime.now(clock));
        }
        if (motivo != null) cita.setMotivoCancelacion(motivo);
        return aRespuesta(citaRepository.saveAndFlush(cita));
    }

    /** La bitácora de la BD lee app.usuario_id para saber quién hizo el cambio (solo dura esta transacción). */
    private void fijarActor(UUID usuarioId) {
        entityManager.createNativeQuery("select set_config('app.usuario_id', :id, true)")
                .setParameter("id", usuarioId.toString())
                .getSingleResult();
    }

    private Cita exigirParticipante(UUID usuarioId, UUID citaId) {
        return citaRepository.findById(citaId)
                .filter(c -> c.getPaciente().getId().equals(usuarioId) || c.getOdontologo().getUsuarioId().equals(usuarioId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cita no encontrada"));
    }

    private Cita exigirDelOdontologo(UUID odontologoId, UUID citaId) {
        return citaRepository.findById(citaId)
                .filter(c -> c.getOdontologo().getUsuarioId().equals(odontologoId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cita no encontrada"));
    }

    private static void exigirEstado(Cita cita, EstadoCita esperado) {
        if (cita.getEstado() != esperado) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La cita está " + cita.getEstado() + "; esta acción requiere " + esperado);
        }
    }

    CitaResponse aRespuesta(Cita c) {
        PerfilOdontologo o = c.getOdontologo();
        Usuario p = c.getPaciente();
        OffsetDateTime pagarAntesDe = c.getEstado() == EstadoCita.PENDIENTE_PAGO && c.getCreadoEn() != null
                ? c.getCreadoEn().plus(PLAZO_PAGO) : null;
        return new CitaResponse(c.getId(), c.getEstado(), c.getInicioProgramado(), c.getFinProgramado(), c.getZonaHoraria(),
                c.getPrecioServicio(), c.getMontoDepositoRequerido(), pagarAntesDe,
                new OdontologoResumen(o.getUsuarioId(), o.getUsuario().getNombreCompleto(), o.getNombreConsultorio(),
                        o.getDireccionConsultorio(), o.getDistritoConsultorio()),
                new PacienteResumen(p.getId(), p.getNombreCompleto(), p.getTelefono(), p.getCorreo()),
                new ServicioResumen(c.getServicio().getId(), c.getServicio().getTitulo()),
                c.getMotivoCancelacion(), c.getCreadoEn());
    }
}

package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.CitaDtos.CitaResponse;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.PagoRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

/**
 * RF05: el paciente cambia la fecha u hora de una cita ya pagada, sin perder el depósito.
 * <ul>
 *   <li>Solo el paciente de la cita, y solo si está CONFIRMADA o PENDIENTE_CONFIRMACION (ya pagó).
 *       Una reserva sin pagar se cancela y se vuelve a reservar.</li>
 *   <li>Con al menos HORAS_MINIMAS_REPROGRAMACION (24 h) de anticipación.</li>
 *   <li>Como máximo MAX_REPROGRAMACIONES_POR_CITA (1) vez: se cuenta la cadena de citas de origen.</li>
 *   <li>Mismo odontólogo y mismo servicio; el nuevo horario tiene que estar libre.</li>
 * </ul>
 * Como pide el diseño de la BD: la cita original pasa a REPROGRAMADA (deja de ocupar el horario) ANTES de crear
 * la nueva, que apunta a ella con cita_origen_id y nace en el mismo estado. El pago se mueve a la cita nueva,
 * así las devoluciones y liquidaciones siguen funcionando igual. Si algo falla, la transacción deshace todo.
 */
@Service
@RequiredArgsConstructor
public class ReprogramacionService {

    public static final String HORAS_MINIMAS = "HORAS_MINIMAS_REPROGRAMACION";
    public static final String MAXIMO = "MAX_REPROGRAMACIONES_POR_CITA";
    private static final Set<EstadoCita> REPROGRAMABLES =
            Set.of(EstadoCita.CONFIRMADA, EstadoCita.PENDIENTE_CONFIRMACION);

    private final CitaRepository citaRepository;
    private final PagoRepository pagoRepository;
    private final DisponibilidadService disponibilidadService;
    private final BloqueoReservas bloqueoReservas;
    private final ParametrosSistema parametros;
    private final EntityManager entityManager;
    private final Clock clock;
    private final ApplicationEventPublisher eventos;
    private final CitaService citaService;

    @Transactional
    public CitaResponse reprogramar(UUID pacienteId, UUID citaId, OffsetDateTime nuevoInicio) {
        Cita original = citaRepository.findById(citaId)
                .filter(c -> c.getPaciente().getId().equals(pacienteId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cita no encontrada"));
        if (original.getEstado() == EstadoCita.PENDIENTE_PAGO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta reserva todavía no está pagada: cancélala y reserva el horario que prefieras");
        }
        if (!REPROGRAMABLES.contains(original.getEstado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Una cita " + original.getEstado() + " ya no se puede reprogramar");
        }
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        int horasMinimas = parametros.entero(HORAS_MINIMAS, 24);
        if (Duration.between(ahora, original.getInicioProgramado()).toMinutes() < horasMinimas * 60L) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo puedes reprogramar con al menos " + horasMinimas + " horas de anticipación");
        }
        int maximo = parametros.entero(MAXIMO, 1);
        if (reprogramacionesPrevias(original) >= maximo) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, maximo == 1
                    ? "Esta cita ya fue reprogramada una vez; no se puede volver a reprogramar"
                    : "Esta cita ya llegó al máximo de " + maximo + " reprogramaciones");
        }

        PerfilOdontologo perfil = original.getOdontologo();
        Servicio servicio = disponibilidadService.exigirServicioActivo(perfil.getUsuarioId(),
                original.getServicio().getId());
        OffsetDateTime inicio = nuevoInicio.atZoneSameInstant(ZoneId.of(perfil.getZonaHoraria())).toOffsetDateTime();
        if (inicio.isEqual(original.getInicioProgramado())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige un horario distinto al actual");
        }

        String candado = BloqueoReservas.clave(perfil.getUsuarioId(), inicio);
        if (!bloqueoReservas.tomar(candado, pacienteId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Otro paciente está reservando ese horario; elige otro");
        }
        try {
            fijarActor(pacienteId);
            // 1) La original deja de ocupar su horario (así también se puede mover a un turno que se cruza con ella)
            EstadoCita estadoAnterior = original.getEstado();
            OffsetDateTime inicioAnterior = original.getInicioProgramado();
            original.setEstado(EstadoCita.REPROGRAMADA);
            citaRepository.saveAndFlush(original);

            // 2) El horario nuevo tiene que estar libre (se revisa ya sin la original)
            boolean turnoLibre = disponibilidadService.turnosLibres(perfil, servicio, inicio.toLocalDate()).stream()
                    .anyMatch(t -> t.inicio().isEqual(inicio));
            if (!turnoLibre) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese horario ya no está disponible; elige otro");
            }

            // 3) La cita nueva, en el mismo estado y apuntando a la original
            Cita nueva = Cita.builder()
                    .paciente(original.getPaciente())
                    .odontologo(perfil)
                    .servicio(servicio)
                    .citaOrigenId(original.getId())
                    .inicioProgramado(inicio)
                    .finProgramado(inicio.plusMinutes(servicio.getDuracionMinutos()))
                    // Los triggers sobrescriben estos 4 valores con la copia del servicio; se envían porque son NOT NULL
                    .zonaHoraria(perfil.getZonaHoraria())
                    .precioServicio(servicio.getPrecioTotal())
                    .montoDepositoRequerido(servicio.getMontoDeposito())
                    .duracionMinutos(servicio.getDuracionMinutos())
                    .estado(estadoAnterior)
                    .referenciaBloqueo(candado)
                    .build();
            Cita guardada = citaRepository.saveAndFlush(nueva);
            entityManager.refresh(guardada);

            // 4) El depósito pagado pasa a la cita nueva
            pagoRepository.moverACita(original.getId(), guardada.getId());

            eventos.publishEvent(new CitaCambioEstado(original.getId(), estadoAnterior, EstadoCita.REPROGRAMADA));
            eventos.publishEvent(new CitaReprogramada(original.getId(), guardada.getId(), inicioAnterior));
            return citaService.aRespuesta(guardada);
        } catch (DataIntegrityViolationException e) {
            // ex_citas_sin_solape: otra reserva ganó el turno entre la comprobación y el insert
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese horario acaba de ser reservado; elige otro");
        } catch (DataAccessException e) {
            // p. ej. un bloqueo de agenda o un cambio de precio que la BD no acepta para el depósito ya pagado
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se pudo reprogramar a ese horario; elige otro o cancela la cita");
        } finally {
            bloqueoReservas.soltar(candado);
        }
    }

    /** Cuántas veces se reprogramó ya esta cita: se recorre la cadena de cita_origen_id hacia atrás. */
    int reprogramacionesPrevias(Cita cita) {
        int veces = 0;
        UUID origen = cita.getCitaOrigenId();
        while (origen != null && veces < 50) {
            veces++;
            origen = citaRepository.findById(origen).map(Cita::getCitaOrigenId).orElse(null);
        }
        return veces;
    }

    /** La bitácora de la BD lee app.usuario_id para saber quién hizo el cambio (solo dura esta transacción). */
    private void fijarActor(UUID usuarioId) {
        entityManager.createNativeQuery("select set_config('app.usuario_id', :id, true)")
                .setParameter("id", usuarioId.toString())
                .getSingleResult();
    }
}

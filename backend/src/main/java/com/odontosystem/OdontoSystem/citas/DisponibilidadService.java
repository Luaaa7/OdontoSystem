package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.citas.CitaDtos.DisponibilidadResponse;
import com.odontosystem.OdontoSystem.citas.CitaDtos.Turno;
import com.odontosystem.OdontoSystem.entity.BloqueoAgenda;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.DiaSemana;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.HorarioAtencion;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.repository.BloqueoAgendaRepository;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.HorarioAtencionRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ServicioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turnos libres de un odontólogo para un servicio en un día (RF06).
 * Turnos = horario semanal de ese día, cortado cada intervalo_minutos, con la duración del servicio;
 * se quitan los que ya pasaron, los que se cruzan con una cita activa y los que caen en un bloqueo.
 */
@Service
@RequiredArgsConstructor
public class DisponibilidadService {

    static final int DIAS_MAXIMOS_ADELANTE = 60;
    /** Las citas que bloquean la agenda (igual que la restricción ex_citas_sin_solape de la BD). */
    static final List<EstadoCita> ESTADOS_QUE_OCUPAN = List.of(EstadoCita.PENDIENTE_PAGO, EstadoCita.CONFIRMADA);

    private final PerfilOdontologoRepository perfilRepository;
    private final ServicioRepository servicioRepository;
    private final HorarioAtencionRepository horarioRepository;
    private final CitaRepository citaRepository;
    private final BloqueoAgendaRepository bloqueoRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DisponibilidadResponse disponibilidad(UUID odontologoId, UUID servicioId, LocalDate fecha) {
        PerfilOdontologo perfil = exigirPerfilPublicable(odontologoId);
        Servicio servicio = exigirServicioActivo(odontologoId, servicioId);
        ZoneId zona = ZoneId.of(perfil.getZonaHoraria());

        LocalDate hoy = LocalDate.now(clock.withZone(zona));
        if (fecha.isBefore(hoy) || fecha.isAfter(hoy.plusDays(DIAS_MAXIMOS_ADELANTE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha debe estar entre hoy y " + DIAS_MAXIMOS_ADELANTE + " días adelante");
        }
        return new DisponibilidadResponse(odontologoId, servicioId, fecha, zona.getId(), servicio.getDuracionMinutos(),
                turnosLibres(perfil, servicio, fecha));
    }

    /** Lo usa también CitaService para comprobar que el turno pedido sigue libre. */
    List<Turno> turnosLibres(PerfilOdontologo perfil, Servicio servicio, LocalDate fecha) {
        ZoneId zona = ZoneId.of(perfil.getZonaHoraria());
        UUID odontologoId = perfil.getUsuarioId();
        DiaSemana dia = DiaSemana.values()[fecha.getDayOfWeek().getValue() - 1];
        Duration duracion = Duration.ofMinutes(servicio.getDuracionMinutos());

        OffsetDateTime inicioDia = fecha.atStartOfDay(zona).toOffsetDateTime();
        OffsetDateTime finDia = fecha.plusDays(1).atStartOfDay(zona).toOffsetDateTime();
        List<Cita> citas = citaRepository.cruzadas(odontologoId, ESTADOS_QUE_OCUPAN, inicioDia, finDia);
        List<BloqueoAgenda> bloqueos = bloqueoRepository.findByOdontologo_UsuarioIdAndInicioBeforeAndFinAfter(
                odontologoId, finDia, inicioDia);
        OffsetDateTime ahora = OffsetDateTime.now(clock);

        List<Turno> turnos = new ArrayList<>();
        for (HorarioAtencion h : horarioRepository.findByOdontologo_UsuarioIdOrderByDiaSemanaAscHoraInicioAsc(odontologoId)) {
            if (h.getDiaSemana() != dia) continue;
            Duration paso = Duration.ofMinutes(h.getIntervaloMinutos());
            ZonedDateTime finTramo = ZonedDateTime.of(fecha, h.getHoraFin(), zona);
            for (ZonedDateTime t = ZonedDateTime.of(fecha, h.getHoraInicio(), zona);
                 !t.plus(duracion).isAfter(finTramo);
                 t = t.plus(paso)) {
                OffsetDateTime inicio = t.toOffsetDateTime();
                OffsetDateTime fin = inicio.plus(duracion);
                if (!inicio.isAfter(ahora)) continue;
                boolean ocupado = citas.stream().anyMatch(c -> seCruzan(inicio, fin, c.getInicioProgramado(), c.getFinProgramado()))
                        || bloqueos.stream().anyMatch(b -> seCruzan(inicio, fin, b.getInicio(), b.getFin()));
                if (!ocupado) turnos.add(new Turno(inicio, fin));
            }
        }
        turnos.sort((a, b) -> a.inicio().compareTo(b.inicio()));
        return turnos;
    }

    static boolean seCruzan(OffsetDateTime inicioA, OffsetDateTime finA, OffsetDateTime inicioB, OffsetDateTime finB) {
        return inicioA.isBefore(finB) && inicioB.isBefore(finA);
    }

    PerfilOdontologo exigirPerfilPublicable(UUID odontologoId) {
        return perfilRepository.findById(odontologoId)
                .filter(p -> p.getDesactivadoEn() == null
                        && p.getEstadoVerificacion() == EstadoVerificacionOdontologo.VERIFICADO
                        && Boolean.TRUE.equals(p.getUsuario().getEstaActivo()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Odontólogo no encontrado"));
    }

    Servicio exigirServicioActivo(UUID odontologoId, UUID servicioId) {
        return servicioRepository.findByIdAndOdontologo_UsuarioId(servicioId, odontologoId)
                .filter(Servicio::isEstaActivo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Servicio no encontrado"));
    }
}

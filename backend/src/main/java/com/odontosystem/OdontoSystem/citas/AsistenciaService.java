package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoReporteAusencia;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.ReporteAusencia;
import com.odontosystem.OdontoSystem.entity.TipoReclamo;
import com.odontosystem.OdontoSystem.reclamos.ReclamoDtos.ReclamoRequest;
import com.odontosystem.OdontoSystem.reclamos.ReclamoService;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.HistoriaClinicaRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ReporteAusenciaRepository;
import com.odontosystem.OdontoSystem.usuarios.ReputacionService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * "¿Te atendieron?" y "el odontólogo no llegó". Ninguna parte castiga a la otra solo con su palabra:
 * <ol>
 *   <li>El paciente reporta "no llegó" desde 15 min después de la hora de la cita y hasta 72 h después de que
 *       termine. Si lo hace durante la cita (hasta 2 h después del fin) debe estar a menos de 200 m del consultorio.</li>
 *   <li>El reporte abre una disputa: el depósito queda retenido y el odontólogo tiene 48 h para responder.
 *       <ul>
 *         <li>Admite que no atendió → reembolso del 100 % y una falta para él.</li>
 *         <li>Dice que sí atendió y registra la historia clínica de la cita (prueba) → gana él y el paciente
 *             recibe un strike por reporte falso.</li>
 *         <li>Dice que sí atendió pero no deja prueba → pasa a reclamo (con constancia para Indecopi).</li>
 *         <li>No responde → gana el paciente.</li>
 *       </ul></li>
 *   <li>Un paciente que ya reportó en los últimos 90 días no gana solo: su nuevo reporte va directo a reclamo.</li>
 *   <li>3 faltas confirmadas en 90 días suspenden al odontólogo 30 días (no aparece en el buscador).</li>
 *   <li>Una cita confirmada que nadie cierra en 72 h se da por atendida (nadie se quejó).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AsistenciaService {

    static final Duration TOLERANCIA = Duration.ofMinutes(15);
    static final Duration VENTANA_EN_VIVO = Duration.ofHours(2);
    static final Duration PLAZO_REPORTE = Duration.ofHours(72);
    static final Duration PLAZO_RESPUESTA = Duration.ofHours(48);
    static final int DISTANCIA_MAXIMA_METROS = 200;
    static final int DIAS_VENTANA = 90;
    static final int FALTAS_PARA_SUSPENDER = 3;
    static final int DIAS_SUSPENSION = 30;
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter FECHA =
            DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'a las' HH:mm", Locale.of("es", "PE"));

    public record AsistenciaRequest(@NotNull(message = "Indica si te atendieron") Boolean atendido,
                                    Double latitud, Double longitud) {}

    public record AsistenciaResponse(UUID citaId, EstadoCita estadoCita, EstadoReporteAusencia reporte, String mensaje) {}

    public record RespuestaReporteRequest(@NotNull(message = "Indica si atendiste al paciente") Boolean atendi,
                                          @Size(max = 2000, message = "La nota no puede superar los 2000 caracteres")
                                          String nota) {}

    public record ReporteResponse(UUID id, UUID citaId, String paciente, String servicio, OffsetDateTime fechaCita,
                                  EstadoReporteAusencia estado, Integer distanciaMetros, String respuestaOdontologo,
                                  boolean historiaRegistrada, OffsetDateTime responderAntes, OffsetDateTime creadoEn,
                                  OffsetDateTime resueltoEn) {}

    public record ResultadoTarea(int confirmados, int rechazados, int enReclamo, int citasCerradas) {}

    private final CitaRepository citaRepository;
    private final ReporteAusenciaRepository reporteRepository;
    private final HistoriaClinicaRepository historiaRepository;
    private final PerfilOdontologoRepository perfilRepository;
    private final CitaService citaService;
    private final ReputacionService reputacion;
    private final ReembolsoService reembolsos;
    private final ReclamoService reclamoService;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    // ------------------------------------------------------------------ paciente

    @Transactional
    public AsistenciaResponse responderPaciente(UUID pacienteId, UUID citaId, AsistenciaRequest req) {
        Cita cita = citaRepository.findById(citaId)
                .filter(c -> c.getPaciente().getId().equals(pacienteId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cita no encontrada"));
        if (cita.getEstado() != EstadoCita.CONFIRMADA && cita.getEstado() != EstadoCita.COMPLETADA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo puedes responder sobre una cita confirmada");
        }
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        if (ahora.isBefore(cita.getInicioProgramado().plus(TOLERANCIA))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Espera " + TOLERANCIA.toMinutes() + " minutos después de la hora de tu cita");
        }
        if (ahora.isAfter(fin(cita).plus(PLAZO_REPORTE))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pasaron más de 72 horas desde la cita");
        }
        if (reporteRepository.existsByCita_Id(citaId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya respondiste sobre esta cita");
        }

        if (Boolean.TRUE.equals(req.atendido())) {
            if (cita.getEstado() == EstadoCita.CONFIRMADA) {
                cita.setCompletadoEn(ahora);
                citaService.cambiarEstado(cita, pacienteId, EstadoCita.COMPLETADA, null);
                reputacion.quitarStrike(pacienteId);
            }
            return new AsistenciaResponse(citaId, cita.getEstado(), null, "¡Gracias por confirmar! Ya puedes calificar tu cita.");
        }

        Integer distancia = distanciaMetros(cita.getOdontologo(), req.latitud(), req.longitud());
        boolean enVivo = !ahora.isAfter(fin(cita).plus(VENTANA_EN_VIVO));
        if (enVivo && tieneUbicacion(cita.getOdontologo())) {
            if (distancia == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Comparte tu ubicación: para reportarlo durante la cita debes estar en el consultorio");
            }
            if (distancia > DISTANCIA_MAXIMA_METROS) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Estás a " + distancia
                        + " m del consultorio; para reportarlo debes estar a menos de " + DISTANCIA_MAXIMA_METROS + " m");
            }
        }
        boolean repetido = reporteRepository.countByCita_Paciente_IdAndCreadoEnAfter(pacienteId,
                ahora.minusDays(DIAS_VENTANA)) > 0;
        ReporteAusencia reporte = reporteRepository.saveAndFlush(ReporteAusencia.builder()
                .cita(cita)
                .distanciaMetros(distancia)
                .estado(repetido ? EstadoReporteAusencia.EN_RECLAMO : EstadoReporteAusencia.PENDIENTE)
                .creadoEn(ahora)
                .build());
        if (repetido) {
            abrirReclamo(cita);
            return new AsistenciaResponse(citaId, cita.getEstado(), reporte.getEstado(),
                    "Ya hiciste otro reporte en los últimos " + DIAS_VENTANA + " días, así que este pasa a reclamo. "
                            + "Tu depósito queda retenido hasta que se resuelva.");
        }
        eventos.publishEvent(new ReporteAusenciaCreado(reporte.getId()));
        return new AsistenciaResponse(citaId, cita.getEstado(), reporte.getEstado(),
                "Registramos tu reporte. El odontólogo tiene 48 horas para responder; si no lo hace, "
                        + "se te devuelve el 100 % del depósito.");
    }

    // ------------------------------------------------------------------ odontólogo

    @Transactional(readOnly = true)
    public List<ReporteResponse> delOdontologo(UUID odontologoId) {
        return reporteRepository.delOdontologo(odontologoId).stream().map(this::aRespuesta).toList();
    }

    @Transactional
    public ReporteResponse responderOdontologo(UUID odontologoId, UUID reporteId, RespuestaReporteRequest req) {
        ReporteAusencia r = reporteRepository.conDetalle(reporteId)
                .filter(x -> x.getCita().getOdontologo().getUsuarioId().equals(odontologoId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reporte no encontrado"));
        if (r.getEstado() != EstadoReporteAusencia.PENDIENTE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este reporte ya se resolvió");
        }
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        String nota = req.nota() == null || req.nota().isBlank() ? null : req.nota().strip();
        if (!Boolean.TRUE.equals(req.atendi())) {
            r.setRespuestaOdontologo(nota != null ? nota : "El odontólogo admitió que no atendió la cita");
            r.setRespondidoEn(ahora);
            confirmarAusencia(r, ahora);
            return aRespuesta(r);
        }
        r.setRespuestaOdontologo(nota != null ? nota : "Atendí al paciente");
        r.setRespondidoEn(ahora);
        Cita cita = r.getCita();
        if (cita.getEstado() == EstadoCita.CONFIRMADA) {
            cita.setCompletadoEn(ahora);
            citaService.cambiarEstado(cita, odontologoId, EstadoCita.COMPLETADA, null);
        }
        if (historiaRepository.existsByCita_Id(cita.getId())) {
            rechazar(r, ahora);
        } else {
            reporteRepository.save(r); // falta la prueba: puede registrar la historia clínica y volver a responder
        }
        return aRespuesta(r);
    }

    // ------------------------------------------------------------------ tarea programada

    /** Resuelve las disputas vencidas (48 h) y da por atendidas las citas que nadie cerró en 72 h. */
    @Transactional
    public ResultadoTarea procesarVencidos() {
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        int confirmados = 0;
        int rechazados = 0;
        int enReclamo = 0;
        for (ReporteAusencia r : reporteRepository.enEstadoCreadosAntesDe(EstadoReporteAusencia.PENDIENTE,
                ahora.minus(PLAZO_RESPUESTA))) {
            if (historiaRepository.existsByCita_Id(r.getCita().getId())) {
                rechazar(r, ahora);
                rechazados++;
            } else if (r.getRespuestaOdontologo() != null) {
                r.setEstado(EstadoReporteAusencia.EN_RECLAMO);
                r.setResueltoEn(ahora);
                reporteRepository.save(r);
                abrirReclamo(r.getCita());
                enReclamo++;
            } else {
                confirmarAusencia(r, ahora);
                confirmados++;
            }
        }
        int cerradas = 0;
        for (Cita c : citaRepository.terminadasSinReporte(EstadoCita.CONFIRMADA, ahora.minus(PLAZO_REPORTE))) {
            c.setCompletadoEn(fin(c));
            citaService.cambiarEstado(c, null, EstadoCita.COMPLETADA, null);
            reputacion.quitarStrike(c.getPaciente().getId());
            cerradas++;
        }
        return new ResultadoTarea(confirmados, rechazados, enReclamo, cerradas);
    }

    // ------------------------------------------------------------------

    /** El odontólogo faltó: reembolso del 100 %, la cita queda cancelada con reembolso y él suma una falta. */
    private void confirmarAusencia(ReporteAusencia r, OffsetDateTime ahora) {
        r.setEstado(EstadoReporteAusencia.CONFIRMADO);
        r.setResueltoEn(ahora);
        reporteRepository.saveAndFlush(r);
        Cita cita = r.getCita();
        reembolsos.reembolsarDeposito(cita.getId(), "ausencia:" + cita.getId(), "El odontólogo no se presentó a la cita");
        if (cita.getEstado() != EstadoCita.CANCELADA_CON_REEMBOLSO) {
            citaService.cambiarEstado(cita, null, EstadoCita.CANCELADA_CON_REEMBOLSO, "El odontólogo no se presentó a la cita");
        }
        PerfilOdontologo perfil = cita.getOdontologo();
        long faltas = reporteRepository.countByCita_Odontologo_UsuarioIdAndEstadoAndResueltoEnAfter(
                perfil.getUsuarioId(), EstadoReporteAusencia.CONFIRMADO, ahora.minusDays(DIAS_VENTANA));
        if (faltas >= FALTAS_PARA_SUSPENDER) {
            perfil.setSuspendidoHasta(ahora.plusDays(DIAS_SUSPENSION));
            perfilRepository.save(perfil);
        }
    }

    /** El odontólogo probó que atendió: el reporte era falso y el paciente recibe un strike. */
    private void rechazar(ReporteAusencia r, OffsetDateTime ahora) {
        r.setEstado(EstadoReporteAusencia.RECHAZADO);
        r.setResueltoEn(ahora);
        reporteRepository.save(r);
        reputacion.sumarStrike(r.getCita().getPaciente());
    }

    private void abrirReclamo(Cita cita) {
        String servicio = cita.getServicio() != null ? cita.getServicio().getTitulo() : "mi cita";
        reclamoService.registrar(cita.getPaciente().getId(), new ReclamoRequest(TipoReclamo.RECLAMO,
                "El odontólogo no se presentó a mi cita",
                "Reporté que el odontólogo no llegó a mi cita de " + servicio + " del "
                        + FECHA.format(cita.getInicioProgramado().atZoneSameInstant(LIMA))
                        + ". Este reclamo se abrió automáticamente desde el reporte de asistencia.",
                cita.getId()));
    }

    private ReporteResponse aRespuesta(ReporteAusencia r) {
        Cita c = r.getCita();
        return new ReporteResponse(r.getId(), c.getId(), c.getPaciente().getNombreCompleto(),
                c.getServicio() != null ? c.getServicio().getTitulo() : null, c.getInicioProgramado(), r.getEstado(),
                r.getDistanciaMetros(), r.getRespuestaOdontologo(), historiaRepository.existsByCita_Id(c.getId()),
                r.getEstado() == EstadoReporteAusencia.PENDIENTE ? r.getCreadoEn().plus(PLAZO_RESPUESTA) : null,
                r.getCreadoEn(), r.getResueltoEn());
    }

    private static OffsetDateTime fin(Cita c) {
        return c.getFinProgramado() != null ? c.getFinProgramado() : c.getInicioProgramado();
    }

    private static boolean tieneUbicacion(PerfilOdontologo p) {
        return p.getLatitudConsultorio() != null && p.getLongitudConsultorio() != null;
    }

    /** Distancia en metros (fórmula del haversine), o null si falta alguna de las dos ubicaciones. */
    static Integer distanciaMetros(PerfilOdontologo p, Double latitud, Double longitud) {
        if (latitud == null || longitud == null || !tieneUbicacion(p)) return null;
        double lat1 = Math.toRadians(p.getLatitudConsultorio().doubleValue());
        double lat2 = Math.toRadians(latitud);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(longitud - p.getLongitudConsultorio().doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return (int) Math.round(6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }
}

package com.odontosystem.OdontoSystem.apelaciones;

import com.odontosystem.OdontoSystem.apelaciones.ApelacionDtos.ApelacionResponse;
import com.odontosystem.OdontoSystem.archivos.ArchivoService;
import com.odontosystem.OdontoSystem.archivos.ArchivoService.ArchivoSubido;
import com.odontosystem.OdontoSystem.archivos.ArchivoService.EnlaceTemporal;
import com.odontosystem.OdontoSystem.citas.ParametrosSistema;
import com.odontosystem.OdontoSystem.citas.ReembolsoService;
import com.odontosystem.OdontoSystem.entity.Apelacion;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoApelacion;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoReputacion;
import com.odontosystem.OdontoSystem.entity.ReputacionPaciente;
import com.odontosystem.OdontoSystem.repository.ApelacionRepository;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.ReputacionPacienteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Apelaciones por inasistencia (arquitectura sin administrador):
 * <ol>
 *   <li>El paciente con una cita INASISTENCIA_PENALIZADA sube un certificado (PDF o imagen) dentro de los 7 días.</li>
 *   <li>El odontólogo de esa cita la revisa y decide.</li>
 *   <li>Si la aprueba: se le quita un strike al paciente (si estaba BLOQUEADO puede volver a reservar)
 *       y se le devuelve el depósito. Si la rechaza, todo queda igual.</li>
 * </ol>
 * La cita sigue como INASISTENCIA_PENALIZADA en ambos casos: es lo que pasó; la apelación queda como el registro.
 */
@Service
public class ApelacionService {

    static final Duration PLAZO_PARA_APELAR = Duration.ofDays(7);

    private final ApelacionRepository apelacionRepository;
    private final CitaRepository citaRepository;
    private final ReputacionPacienteRepository reputacionRepository;
    private final ArchivoService archivoService;
    private final ReembolsoService reembolsoService;
    private final ParametrosSistema parametros;
    private final Clock clock;

    public ApelacionService(ApelacionRepository apelacionRepository, CitaRepository citaRepository,
                            ReputacionPacienteRepository reputacionRepository, ArchivoService archivoService,
                            ReembolsoService reembolsoService, ParametrosSistema parametros, Clock clock) {
        this.apelacionRepository = apelacionRepository;
        this.citaRepository = citaRepository;
        this.reputacionRepository = reputacionRepository;
        this.archivoService = archivoService;
        this.reembolsoService = reembolsoService;
        this.parametros = parametros;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ paciente

    @Transactional
    public ApelacionResponse apelar(UUID pacienteId, UUID citaId, MultipartFile certificado) {
        Cita cita = citaRepository.findById(citaId)
                .filter(c -> c.getPaciente().getId().equals(pacienteId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cita no encontrada"));
        if (cita.getEstado() != EstadoCita.INASISTENCIA_PENALIZADA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo se puede apelar una inasistencia registrada");
        }
        if (cita.getInicioProgramado().plus(PLAZO_PARA_APELAR).isBefore(OffsetDateTime.now(clock))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Pasaron más de " + PLAZO_PARA_APELAR.toDays() + " días desde la cita; ya no se puede apelar");
        }
        if (apelacionRepository.existsByCita_Id(citaId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya enviaste una apelación para esta cita");
        }
        ArchivoSubido subido = archivoService.subirDocumentoPrivado(certificado, "apelaciones/" + pacienteId);
        try {
            Apelacion apelacion = apelacionRepository.saveAndFlush(Apelacion.builder()
                    .cita(cita).urlCertificado(subido.url()).build());
            return aRespuesta(apelacion, null);
        } catch (RuntimeException e) {
            archivoService.eliminar(subido.url()); // sin apelación guardada no tiene sentido conservar el archivo
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public ApelacionResponse deMiCita(UUID pacienteId, UUID citaId) {
        Apelacion apelacion = apelacionRepository.findByCita_Id(citaId)
                .filter(a -> a.getCita().getPaciente().getId().equals(pacienteId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay apelación para esta cita"));
        return aRespuesta(apelacion, null);
    }

    // ------------------------------------------------------------------ odontólogo

    @Transactional(readOnly = true)
    public List<ApelacionResponse> delOdontologo(UUID odontologoId, EstadoApelacion estado) {
        List<Apelacion> apelaciones = estado == null
                ? apelacionRepository.delOdontologo(odontologoId)
                : apelacionRepository.delOdontologoEnEstado(odontologoId, estado);
        return apelaciones.stream()
                .map(a -> aRespuesta(a, strikesDe(a.getCita().getPaciente().getId())))
                .toList();
    }

    @Transactional
    public ApelacionResponse resolver(UUID odontologoId, UUID apelacionId, boolean aprobar, String nota) {
        Apelacion apelacion = apelacionRepository.conDetalle(apelacionId)
                .filter(a -> a.getCita().getOdontologo().getUsuarioId().equals(odontologoId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Apelación no encontrada"));
        if (apelacion.getEstado() != EstadoApelacion.PENDIENTE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta apelación ya fue resuelta");
        }
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        apelacion.setEstado(aprobar ? EstadoApelacion.APROBADA : EstadoApelacion.RECHAZADA);
        apelacion.setResueltoPorOdontologoId(odontologoId);
        apelacion.setNotaResolucion(nota == null || nota.isBlank() ? null : nota.strip());
        apelacion.setResueltoEn(ahora);
        apelacionRepository.saveAndFlush(apelacion);

        UUID pacienteId = apelacion.getCita().getPaciente().getId();
        if (aprobar) {
            quitarUnStrike(pacienteId, ahora);
            reembolsoService.reembolsarDeposito(apelacion.getCita().getId(), "apelacion:" + apelacion.getCita().getId(),
                    "Apelación aprobada: inasistencia justificada");
        }
        return aRespuesta(apelacion, strikesDe(pacienteId));
    }

    // ------------------------------------------------------------------

    /** Un strike menos; el estado se recalcula (0 = EXCELENTE, debajo del límite = REGULAR). */
    private void quitarUnStrike(UUID pacienteId, OffsetDateTime ahora) {
        reputacionRepository.findById(pacienteId).ifPresent(r -> {
            int strikes = Math.max(0, r.getCantidadStrikes() - 1);
            int limite = parametros.entero(ParametrosSistema.LIMITE_STRIKES, 3);
            r.setCantidadStrikes((short) strikes);
            r.setEstado(strikes == 0 ? EstadoReputacion.EXCELENTE
                    : strikes < limite ? EstadoReputacion.REGULAR : EstadoReputacion.BLOQUEADO);
            if (r.getEstado() != EstadoReputacion.BLOQUEADO) {
                r.setRestringidoHasta(null);
            }
            r.setActualizadoEn(ahora);
            reputacionRepository.save(r);
        });
    }

    private Short strikesDe(UUID pacienteId) {
        return reputacionRepository.findById(pacienteId).map(ReputacionPaciente::getCantidadStrikes).orElse((short) 0);
    }

    private ApelacionResponse aRespuesta(Apelacion a, Short strikes) {
        Cita c = a.getCita();
        EnlaceTemporal enlace = null;
        try {
            enlace = archivoService.enlaceTemporal(a.getUrlCertificado());
        } catch (RuntimeException e) {
            // Cloudinary no configurado o caído: la apelación se muestra igual, sin el enlace
        }
        return new ApelacionResponse(a.getId(), c.getId(), a.getEstado(), c.getPaciente().getNombreCompleto(),
                c.getServicio() != null ? c.getServicio().getTitulo() : null, c.getInicioProgramado(),
                enlace != null ? enlace.url() : null, enlace != null ? enlace.expiraEn() : null,
                a.getNotaResolucion(), a.getCreadoEn(), a.getResueltoEn(), strikes);
    }
}

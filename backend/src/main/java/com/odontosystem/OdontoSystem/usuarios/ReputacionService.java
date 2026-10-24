package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.citas.ParametrosSistema;
import com.odontosystem.OdontoSystem.entity.EstadoReputacion;
import com.odontosystem.OdontoSystem.entity.ReputacionPaciente;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.ReputacionPacienteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Puntualidad del paciente (RF05 / RF09). Es privada: solo la ve el propio
 * paciente y, como semáforo,
 * el odontólogo de sus citas. No hay estrellas públicas para pacientes.
 * <ul>
 * <li>+1 strike: no se presenta a una cita confirmada, o la cancela con menos
 * de
 * HORAS_CANCELACION_CON_STRIKE (2 h) de anticipación.</li>
 * <li>−1 strike: asiste a una cita (COMPLETADA) o le aprueban una
 * apelación.</li>
 * <li>Al llegar a LIMITE_MAXIMO_STRIKES (3): suspendido DIAS_SUSPENSION_STRIKES
 * (30) días sin poder reservar.
 * Al terminar la suspensión vuelve a 0 strikes.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReputacionService {

    public static final String DIAS_SUSPENSION = "DIAS_SUSPENSION_STRIKES";

    public record ReputacionResponse(short strikes, int limiteStrikes, EstadoReputacion estado,
            OffsetDateTime restringidoHasta, boolean puedeReservar) {
    }

    private final ReputacionPacienteRepository reputacionRepository;
    private final ParametrosSistema parametros;
    private final Clock clock;
    private final ApplicationEventPublisher eventos;

    @Transactional(readOnly = true)
    public ReputacionResponse deMiCuenta(UUID pacienteId) {
        int limite = limite();
        ReputacionPaciente r = reputacionRepository.findById(pacienteId).orElse(null);
        if (r == null || suspensionCumplida(r)) {
            return new ReputacionResponse((short) 0, limite, EstadoReputacion.EXCELENTE, null, true);
        }
        return new ReputacionResponse(r.getCantidadStrikes(), limite, r.getEstado(), r.getRestringidoHasta(),
                !bloqueado(r));
    }

    /**
     * Antes de reservar: 403 si está suspendido. Si la suspensión ya terminó, lo
     * deja en 0 strikes.
     */
    @Transactional
    public void exigirQuePuedaReservar(UUID pacienteId) {
        reputacionRepository.findById(pacienteId).ifPresent(r -> {
            if (suspensionCumplida(r)) {
                reiniciar(r);
                return;
            }
            if (bloqueado(r)) {
                String hasta = r.getRestringidoHasta() == null ? ""
                        : " hasta el " + r.getRestringidoHasta().toLocalDate();
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Tu cuenta no puede reservar" + hasta + " por inasistencias o cancelaciones de último momento");
            }
        });
    }

    /**
     * Suma un strike; con el límite la cuenta queda suspendida por
     * DIAS_SUSPENSION_STRIKES días.
     */
    @Transactional
    public ReputacionPaciente sumarStrike(Usuario paciente) {
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        ReputacionPaciente r = reputacionRepository.findById(paciente.getId())
                .orElseGet(() -> ReputacionPaciente.builder().usuario(paciente).build());
        if (suspensionCumplida(r)) {
            r.setCantidadStrikes((short) 0);
            r.setRestringidoHasta(null);
        }
        int limite = limite();
        short antes = r.getCantidadStrikes();
        short strikes = (short) Math.min(limite, antes + 1);
        r.setCantidadStrikes(strikes);
        if (strikes >= limite) {
            r.setEstado(EstadoReputacion.BLOQUEADO);
            if (r.getRestringidoHasta() == null || !r.getRestringidoHasta().isAfter(ahora)) {
                r.setRestringidoHasta(ahora.plusDays(parametros.entero(DIAS_SUSPENSION, 30)));
            }
        } else {
            r.setEstado(EstadoReputacion.REGULAR);
        }
        r.setActualizadoEn(ahora);
        ReputacionPaciente guardada = reputacionRepository.save(r);
        // Si ya estaba en el límite el contador no sube: no se avisa dos veces lo
        // mismo.
        if (strikes > antes) {
            eventos.publishEvent(new StrikeSumado(paciente.getId(), paciente.getNombreCompleto(),
                    paciente.getCorreo(), strikes, (short) limite, r.getRestringidoHasta()));
        }
        return guardada;
    }

    /**
     * Quita un strike (asistió o le aprobaron una apelación). Si estaba suspendido
     * y baja del límite,
     * la suspensión se levanta.
     */
    @Transactional
    public void quitarStrike(UUID pacienteId) {
        reputacionRepository.findById(pacienteId).ifPresent(r -> {
            if (r.getCantidadStrikes() <= 0)
                return;
            int strikes = r.getCantidadStrikes() - 1;
            r.setCantidadStrikes((short) strikes);
            r.setEstado(strikes == 0 ? EstadoReputacion.EXCELENTE
                    : strikes < limite() ? EstadoReputacion.REGULAR : EstadoReputacion.BLOQUEADO);
            if (r.getEstado() != EstadoReputacion.BLOQUEADO) {
                r.setRestringidoHasta(null);
            }
            r.setActualizadoEn(OffsetDateTime.now(clock));
            reputacionRepository.save(r);
        });
    }

    @Transactional(readOnly = true)
    public short strikesDe(UUID pacienteId) {
        return reputacionRepository.findById(pacienteId)
                .filter(r -> !suspensionCumplida(r))
                .map(ReputacionPaciente::getCantidadStrikes)
                .orElse((short) 0);
    }

    // ------------------------------------------------------------------

    private int limite() {
        return parametros.entero(ParametrosSistema.LIMITE_STRIKES, 3);
    }

    /**
     * Bloqueado = suspensión vigente, o BLOQUEADO sin fecha (cuentas antiguas: solo
     * se libera por apelación).
     */
    private boolean bloqueado(ReputacionPaciente r) {
        OffsetDateTime hasta = r.getRestringidoHasta();
        if (hasta != null)
            return hasta.isAfter(OffsetDateTime.now(clock));
        return r.getEstado() == EstadoReputacion.BLOQUEADO;
    }

    private boolean suspensionCumplida(ReputacionPaciente r) {
        return r.getRestringidoHasta() != null && !r.getRestringidoHasta().isAfter(OffsetDateTime.now(clock));
    }

    private void reiniciar(ReputacionPaciente r) {
        r.setCantidadStrikes((short) 0);
        r.setEstado(EstadoReputacion.EXCELENTE);
        r.setRestringidoHasta(null);
        r.setActualizadoEn(OffsetDateTime.now(clock));
        reputacionRepository.save(r);
    }
}

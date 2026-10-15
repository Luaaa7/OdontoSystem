package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.citas.ParametrosSistema;
import com.odontosystem.OdontoSystem.entity.EstadoReputacion;
import com.odontosystem.OdontoSystem.entity.ReputacionPaciente;
import com.odontosystem.OdontoSystem.repository.ReputacionPacienteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Semáforo de puntualidad del paciente: cuántos strikes tiene (inasistencias sin aviso, menos las apelaciones
 * aprobadas) y si todavía puede reservar. Es lo mismo que revisa CitaService antes de aceptar una reserva.
 */
@Service
@RequiredArgsConstructor
public class ReputacionService {

    public record ReputacionResponse(short strikes, int limiteStrikes, EstadoReputacion estado,
                                     OffsetDateTime restringidoHasta, boolean puedeReservar) {}

    private final ReputacionPacienteRepository reputacionRepository;
    private final ParametrosSistema parametros;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ReputacionResponse deMiCuenta(UUID pacienteId) {
        int limite = parametros.entero(ParametrosSistema.LIMITE_STRIKES, 3);
        ReputacionPaciente r = reputacionRepository.findById(pacienteId).orElse(null);
        if (r == null) {
            return new ReputacionResponse((short) 0, limite, EstadoReputacion.EXCELENTE, null, true);
        }
        boolean restringido = r.getRestringidoHasta() != null && r.getRestringidoHasta().isAfter(OffsetDateTime.now(clock));
        boolean puede = r.getEstado() != EstadoReputacion.BLOQUEADO && !restringido;
        return new ReputacionResponse(r.getCantidadStrikes(), limite, r.getEstado(), r.getRestringidoHasta(), puede);
    }
}

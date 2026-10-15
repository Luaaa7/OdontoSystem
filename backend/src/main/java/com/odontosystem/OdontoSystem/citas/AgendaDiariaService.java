package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.entity.BloqueoAgenda;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.ConfirmacionAgenda;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.BloqueoAgendaRepository;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.ConfirmacionAgendaRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Confirmación diaria de agenda: cada mañana el odontólogo responde "¿atiendes hoy?".
 * <ul>
 *   <li>Sí: se pueden hacer reservas para ese mismo día.</li>
 *   <li>No: se cancelan sus citas pendientes de ese día (reembolso del 100 % a cada paciente, con correo)
 *       y el resto del día queda bloqueado.</li>
 *   <li>Sin respuesta: sus citas ya reservadas siguen en pie, pero no se aceptan reservas nuevas para hoy.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AgendaDiariaService {

    static final List<EstadoCita> ACTIVAS =
            List.of(EstadoCita.PENDIENTE_PAGO, EstadoCita.PENDIENTE_CONFIRMACION, EstadoCita.CONFIRMADA);
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    /** atiende = null: todavía no respondió hoy. */
    public record AgendaHoyResponse(LocalDate fecha, Boolean atiende, OffsetDateTime respondidoEn, int citasHoy,
                                    OffsetDateTime primeraCita, int citasCanceladas) {}

    public record ConfirmacionRequest(@NotNull(message = "Indica si atiendes hoy") Boolean atiende,
                                      @Size(max = 255, message = "El motivo no puede superar los 255 caracteres")
                                      String motivo) {}

    /** Para el correo de la mañana: un odontólogo con citas hoy que todavía no confirmó. */
    public record AvisoAgenda(String correo, String nombre, int citas, OffsetDateTime primeraCita) {}

    private final ConfirmacionAgendaRepository confirmacionRepository;
    private final CitaRepository citaRepository;
    private final PerfilOdontologoRepository perfilRepository;
    private final BloqueoAgendaRepository bloqueoRepository;
    private final CitaService citaService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AgendaHoyResponse hoy(UUID odontologoId) {
        PerfilOdontologo perfil = exigirPerfil(odontologoId);
        return respuesta(perfil, confirmacionRepository.findByOdontologoIdAndFecha(odontologoId, hoy(perfil)).orElse(null), 0);
    }

    @Transactional
    public AgendaHoyResponse confirmar(UUID odontologoId, ConfirmacionRequest req) {
        PerfilOdontologo perfil = exigirPerfil(odontologoId);
        LocalDate hoy = hoy(perfil);
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        ConfirmacionAgenda c = confirmacionRepository.findByOdontologoIdAndFecha(odontologoId, hoy).orElse(null);
        if (c != null && !c.isAtiende()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya indicaste que hoy no atiendes; tus citas de hoy ya se cancelaron");
        }
        if (c != null && req.atiende()) {
            return respuesta(perfil, c, 0); // ya había confirmado: no cambia nada
        }
        if (c == null) {
            c = ConfirmacionAgenda.builder().odontologoId(odontologoId).fecha(hoy).build();
        }
        String motivo = req.motivo() == null || req.motivo().isBlank() ? null : req.motivo().strip();
        c.setAtiende(req.atiende());
        c.setMotivo(motivo);
        c.setRespondidoEn(ahora);
        confirmacionRepository.save(c);

        int canceladas = 0;
        if (!req.atiende()) {
            OffsetDateTime finDia = finDelDia(perfil, hoy);
            String texto = "El odontólogo no puede atender hoy" + (motivo == null ? "" : ": " + motivo);
            for (Cita cita : citaRepository.cruzadas(odontologoId, ACTIVAS, ahora, finDia)) {
                if (!cita.getInicioProgramado().isAfter(ahora)) continue; // ya empezó: no se cancela aquí
                citaService.cancelar(odontologoId, cita.getId(), texto);
                canceladas++;
            }
            bloqueoRepository.save(BloqueoAgenda.builder().odontologo(perfil).inicio(ahora).fin(finDia)
                    .motivo("No atiende hoy").build());
        }
        return respuesta(perfil, c, canceladas);
    }

    /** Odontólogos con citas hoy que todavía no respondieron (para el correo de las 6:30 a. m.). */
    @Transactional(readOnly = true)
    public List<AvisoAgenda> pendientesDeConfirmar() {
        LocalDate hoy = LocalDate.now(clock.withZone(LIMA));
        List<Cita> citas = citaRepository.empiezanEntre(List.of(EstadoCita.CONFIRMADA, EstadoCita.PENDIENTE_CONFIRMACION),
                hoy.atStartOfDay(LIMA).toOffsetDateTime(), hoy.plusDays(1).atStartOfDay(LIMA).toOffsetDateTime());
        Map<UUID, List<Cita>> porOdontologo = new LinkedHashMap<>();
        for (Cita c : citas) {
            porOdontologo.computeIfAbsent(c.getOdontologo().getUsuarioId(), k -> new java.util.ArrayList<>()).add(c);
        }
        return porOdontologo.entrySet().stream()
                .filter(e -> confirmacionRepository.findByOdontologoIdAndFecha(e.getKey(), hoy).isEmpty())
                .map(e -> {
                    Usuario u = e.getValue().get(0).getOdontologo().getUsuario();
                    return new AvisoAgenda(u.getCorreo(), u.getNombreCompleto(), e.getValue().size(),
                            e.getValue().get(0).getInicioProgramado());
                })
                .filter(a -> a.correo() != null && !a.correo().isBlank())
                .toList();
    }

    // ------------------------------------------------------------------

    private AgendaHoyResponse respuesta(PerfilOdontologo perfil, ConfirmacionAgenda c, int canceladas) {
        LocalDate hoy = hoy(perfil);
        List<Cita> citas = citaRepository.cruzadas(perfil.getUsuarioId(), ACTIVAS,
                hoy.atStartOfDay(zona(perfil)).toOffsetDateTime(), finDelDia(perfil, hoy));
        OffsetDateTime primera = citas.stream().map(Cita::getInicioProgramado).min(OffsetDateTime::compareTo).orElse(null);
        return new AgendaHoyResponse(hoy, c == null ? null : c.isAtiende(), c == null ? null : c.getRespondidoEn(),
                citas.size(), primera, canceladas);
    }

    private PerfilOdontologo exigirPerfil(UUID odontologoId) {
        return perfilRepository.findById(odontologoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Perfil de odontólogo no encontrado"));
    }

    private LocalDate hoy(PerfilOdontologo perfil) {
        return LocalDate.now(clock.withZone(zona(perfil)));
    }

    private static OffsetDateTime finDelDia(PerfilOdontologo perfil, LocalDate dia) {
        return dia.plusDays(1).atStartOfDay(zona(perfil)).toOffsetDateTime();
    }

    private static ZoneId zona(PerfilOdontologo perfil) {
        return perfil.getZonaHoraria() == null ? LIMA : ZoneId.of(perfil.getZonaHoraria());
    }
}

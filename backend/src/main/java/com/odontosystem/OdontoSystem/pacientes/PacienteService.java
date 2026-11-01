package com.odontosystem.OdontoSystem.pacientes;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Los pacientes de un odontólogo: todos los que tuvieron al menos una cita con él, en cualquier fecha.
 * <ul>
 *   <li>Un odontólogo solo ve a sus pacientes: si no hubo ninguna cita entre los dos, responde 404
 *       (no se revela si el paciente existe).</li>
 *   <li>Solo se devuelven los datos que el odontólogo necesita para atender (sin DNI ni contraseña).</li>
 *   <li>Alergias y antecedentes todavía no se registran en el sistema: van en null y el frontend debe mostrar
 *       "Sin datos", nunca un valor inventado. Lo clínico de cada atención está en las historias clínicas.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PacienteService {

    /** Citas que todavía no ocurrieron y siguen en pie. */
    static final Set<EstadoCita> ACTIVAS =
            Set.of(EstadoCita.PENDIENTE_PAGO, EstadoCita.PENDIENTE_CONFIRMACION, EstadoCita.CONFIRMADA);

    public record PacienteResumen(UUID id, String nombreCompleto, String correo, String telefono,
                                  int totalCitas, int citasAtendidas, int inasistencias,
                                  OffsetDateTime ultimaAtencion, OffsetDateTime proximaCita) {}

    public record PacienteDetalle(UUID id, String nombre, String apellido, String nombreCompleto,
                                  String correo, String telefono, String alergias, String antecedentes,
                                  int totalCitas, int citasAtendidas, int inasistencias,
                                  OffsetDateTime ultimaAtencion, OffsetDateTime proximaCita) {}

    private final CitaRepository citaRepository;
    private final Clock clock;

    /** Todos los pacientes del odontólogo; primero los que tienen una cita próxima, luego por última atención. */
    @Transactional(readOnly = true)
    public List<PacienteResumen> deOdontologo(UUID odontologoId) {
        Map<UUID, List<Cita>> porPaciente = new LinkedHashMap<>();
        for (Cita c : citaRepository.todasDelOdontologo(odontologoId)) {
            porPaciente.computeIfAbsent(c.getPaciente().getId(), k -> new ArrayList<>()).add(c);
        }
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        return porPaciente.values().stream()
                .map(citas -> resumen(citas, ahora))
                .sorted(Comparator.comparing(PacienteResumen::proximaCita, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PacienteResumen::ultimaAtencion, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PacienteResumen::nombreCompleto, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Detalle de un paciente para el historial clínico. 404 si no tuvo citas con este odontólogo. */
    @Transactional(readOnly = true)
    public PacienteDetalle detalle(UUID odontologoId, UUID pacienteId) {
        List<Cita> citas = citaRepository.delPacienteConOdontologo(odontologoId, pacienteId);
        if (citas.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Paciente no encontrado");
        }
        PacienteResumen r = resumen(citas, OffsetDateTime.now(clock));
        String[] partes = separarNombre(r.nombreCompleto());
        return new PacienteDetalle(r.id(), partes[0], partes[1], r.nombreCompleto(), r.correo(), r.telefono(),
                null, null, r.totalCitas(), r.citasAtendidas(), r.inasistencias(), r.ultimaAtencion(), r.proximaCita());
    }

    // ------------------------------------------------------------------

    static PacienteResumen resumen(List<Cita> citas, OffsetDateTime ahora) {
        Usuario p = citas.get(0).getPaciente();
        int atendidas = (int) citas.stream().filter(c -> c.getEstado() == EstadoCita.COMPLETADA).count();
        int inasistencias = (int) citas.stream().filter(c -> c.getEstado() == EstadoCita.INASISTENCIA_PENALIZADA).count();
        OffsetDateTime ultima = citas.stream()
                .filter(c -> c.getEstado() == EstadoCita.COMPLETADA)
                .map(Cita::getInicioProgramado).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        OffsetDateTime proxima = citas.stream()
                .filter(c -> ACTIVAS.contains(c.getEstado()))
                .map(Cita::getInicioProgramado).filter(Objects::nonNull)
                .filter(t -> t.isAfter(ahora))
                .min(Comparator.naturalOrder()).orElse(null);
        return new PacienteResumen(p.getId(), p.getNombreCompleto(), p.getCorreo(), p.getTelefono(),
                citas.size(), atendidas, inasistencias, ultima, proxima);
    }

    /** "Nadia Chávez Huamán" → ["Nadia", "Chávez Huamán"]. Con un solo nombre, el apellido queda vacío. */
    static String[] separarNombre(String nombreCompleto) {
        if (nombreCompleto == null || nombreCompleto.isBlank()) {
            return new String[]{"", ""};
        }
        String[] partes = nombreCompleto.strip().split("\\s+", 2);
        return new String[]{partes[0], partes.length > 1 ? partes[1] : ""};
    }
}

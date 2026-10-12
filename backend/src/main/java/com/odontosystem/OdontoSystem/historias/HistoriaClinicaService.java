package com.odontosystem.OdontoSystem.historias;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.HistoriaClinica;
import com.odontosystem.OdontoSystem.historias.HistoriaDtos.HistoriaRequest;
import com.odontosystem.OdontoSystem.historias.HistoriaDtos.HistoriaResponse;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.HistoriaClinicaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Historia clínica por cita (Ley N.° 29733).
 * <ul>
 *   <li>La escribe el odontólogo de la cita, solo cuando ya fue atendida (COMPLETADA), una vez por cita.
 *       No se edita: un registro clínico no se reescribe en silencio.</li>
 *   <li>La lee el paciente (todas las suyas) y el odontólogo que la escribió. Un odontólogo no ve las
 *       historias que escribieron otros odontólogos del mismo paciente.</li>
 * </ul>
 * El contenido se cifra antes de llegar a la BD (ver {@link CifradoClinico}).
 */
@Service
@RequiredArgsConstructor
public class HistoriaClinicaService {

    private final HistoriaClinicaRepository historiaRepository;
    private final CitaRepository citaRepository;
    private final CifradoClinico cifrado;

    @Transactional
    public HistoriaResponse registrar(UUID odontologoId, UUID citaId, HistoriaRequest req) {
        if (!cifrado.disponible()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Las historias clínicas no están disponibles: falta configurar el cifrado");
        }
        Cita cita = citaRepository.findById(citaId)
                .filter(c -> c.getOdontologo().getUsuarioId().equals(odontologoId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cita no encontrada"));
        if (cita.getEstado() != EstadoCita.COMPLETADA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La historia clínica se registra después de atender la cita (marcarla como completada)");
        }
        if (historiaRepository.existsByCita_Id(citaId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta cita ya tiene historia clínica");
        }
        String notas = req.notas() == null || req.notas().isBlank() ? null : req.notas().strip();
        HistoriaClinica historia = historiaRepository.saveAndFlush(HistoriaClinica.builder()
                .cita(cita)
                .pacienteId(cita.getPaciente().getId())
                .creadoPorOdontologoId(odontologoId)
                .diagnosticoCifrado(cifrado.cifrar(req.diagnostico().strip(), contexto(citaId, "diagnostico")))
                .tratamientoCifrado(cifrado.cifrar(req.tratamiento().strip(), contexto(citaId, "tratamiento")))
                .notasClinicasCifradas(notas == null ? null : cifrado.cifrar(notas, contexto(citaId, "notas")))
                .build());
        return aRespuesta(historia);
    }

    /** La historia de una cita: solo su paciente o el odontólogo que la escribió. */
    @Transactional(readOnly = true)
    public HistoriaResponse deCita(UUID usuarioId, UUID citaId) {
        HistoriaClinica historia = historiaRepository.deCita(citaId)
                .filter(h -> h.getPacienteId().equals(usuarioId) || usuarioId.equals(h.getCreadoPorOdontologoId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Esta cita no tiene historia clínica"));
        return aRespuesta(historia);
    }

    @Transactional(readOnly = true)
    public List<HistoriaResponse> delPaciente(UUID pacienteId) {
        return historiaRepository.delPaciente(pacienteId).stream().map(this::aRespuesta).toList();
    }

    @Transactional(readOnly = true)
    public List<HistoriaResponse> escritasPor(UUID odontologoId, UUID pacienteId) {
        return historiaRepository.delPacienteEscritasPor(pacienteId, odontologoId).stream().map(this::aRespuesta).toList();
    }

    // ------------------------------------------------------------------

    /** Ata cada valor cifrado a su cita y a su campo: no se puede copiar el diagnóstico de una fila a otra. */
    static String contexto(UUID citaId, String campo) {
        return "historia:" + citaId + ":" + campo;
    }

    private HistoriaResponse aRespuesta(HistoriaClinica h) {
        Cita c = h.getCita();
        UUID citaId = c.getId();
        return new HistoriaResponse(h.getId(), citaId, h.getPacienteId(),
                c.getOdontologo().getUsuario() != null ? c.getOdontologo().getUsuario().getNombreCompleto() : null,
                c.getServicio() != null ? c.getServicio().getTitulo() : null, c.getInicioProgramado(),
                cifrado.descifrar(h.getDiagnosticoCifrado(), contexto(citaId, "diagnostico")),
                cifrado.descifrar(h.getTratamientoCifrado(), contexto(citaId, "tratamiento")),
                h.getNotasClinicasCifradas() == null ? null
                        : cifrado.descifrar(h.getNotasClinicasCifradas(), contexto(citaId, "notas")),
                h.getCreadoEn());
    }
}

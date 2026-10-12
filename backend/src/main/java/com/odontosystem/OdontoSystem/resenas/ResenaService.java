package com.odontosystem.OdontoSystem.resenas;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.Resena;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.Pagina;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ResenaRepository;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenaRequest;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Reseñas de pacientes sobre citas atendidas. La BD garantiza una reseña por cita, cita COMPLETADA,
 * calificación de 1 a 5 y recalcula calificacion_promedio / total_resenas del odontólogo con triggers;
 * aquí solo se validan las reglas para devolver mensajes claros.
 */
@Service
@RequiredArgsConstructor
public class ResenaService {

    static final int TAMANO_MAXIMO = 50;

    private final ResenaRepository resenaRepository;
    private final CitaRepository citaRepository;
    private final PerfilOdontologoRepository perfilRepository;

    @Transactional
    public ResenaResponse crear(UUID pacienteId, UUID citaId, ResenaRequest req) {
        // 404 (y no 403) si la cita es de otro paciente: así no se revela que existe
        Cita cita = citaRepository.findById(citaId)
                .filter(c -> c.getPaciente().getId().equals(pacienteId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cita no encontrada"));
        if (cita.getEstado() != EstadoCita.COMPLETADA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo puedes calificar una cita que ya fue atendida");
        }
        if (resenaRepository.existsByCita_Id(citaId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya calificaste esta cita");
        }

        Resena resena = Resena.builder()
                .cita(cita)
                .odontologo(cita.getOdontologo())
                .paciente(cita.getPaciente())
                .calificacion(req.calificacion())
                .comentario(limpiar(req.comentario()))
                .build();
        try {
            return aRespuesta(resenaRepository.saveAndFlush(resena));
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(cita_id): otra petición se adelantó entre la comprobación y el insert
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya calificaste esta cita");
        }
    }

    @Transactional(readOnly = true)
    public Pagina<ResenaResponse> delOdontologo(UUID odontologoId, int pagina, int tamano) {
        exigirPerfilPublicable(odontologoId);
        int tam = tamano <= 0 ? 20 : Math.min(tamano, TAMANO_MAXIMO);
        int pag = Math.max(pagina, 0);
        Page<Resena> resultado = resenaRepository.delOdontologo(odontologoId, PageRequest.of(pag, tam));
        return new Pagina<>(resultado.getContent().stream().map(this::aRespuesta).toList(),
                pag, tam, resultado.getTotalElements(), resultado.getTotalPages());
    }

    // Misma regla que el perfil público: verificado, activo y no desactivado
    private void exigirPerfilPublicable(UUID odontologoId) {
        perfilRepository.findById(odontologoId)
                .filter(p -> p.getDesactivadoEn() == null
                        && p.getEstadoVerificacion() == EstadoVerificacionOdontologo.VERIFICADO
                        && Boolean.TRUE.equals(p.getUsuario().getEstaActivo()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Odontólogo no encontrado"));
    }

    private ResenaResponse aRespuesta(Resena r) {
        return new ResenaResponse(r.getId(), r.getCalificacion(), r.getComentario(),
                nombrePublico(r.getPaciente().getNombreCompleto()), r.getCreadoEn());
    }

    /** "Juan Carlos Pérez Quispe" → "Juan Q." (primer nombre + inicial del último apellido). */
    static String nombrePublico(String nombreCompleto) {
        if (nombreCompleto == null || nombreCompleto.isBlank()) return "Paciente";
        String[] partes = nombreCompleto.trim().split("\\s+");
        if (partes.length == 1) return partes[0];
        return partes[0] + " " + Character.toUpperCase(partes[partes.length - 1].charAt(0)) + ".";
    }

    private static String limpiar(String texto) {
        if (texto == null) return null;
        String limpio = texto.trim();
        return limpio.isEmpty() ? null : limpio;
    }
}
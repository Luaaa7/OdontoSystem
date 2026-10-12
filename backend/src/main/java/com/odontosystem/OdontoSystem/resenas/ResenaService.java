package com.odontosystem.OdontoSystem.resenas;

import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.Resena;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ResenaRepository;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.CitaPorResenar;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenaPublica;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenaRequest;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenaResponse;
import com.odontosystem.OdontoSystem.resenas.ResenaDtos.ResenasOdontologoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reseñas verificadas: solo el paciente de una cita COMPLETADA puede calificarla, una sola vez (1 a 5 estrellas
 * y un comentario opcional). Puede corregir su reseña después. El promedio y el total del odontólogo los
 * recalculan los triggers de la BD; con eso el buscador ordena por "mejor calificados".
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
        Cita cita = citaRepository.findById(citaId)
                .filter(c -> c.getPaciente().getId().equals(pacienteId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cita no encontrada"));
        if (cita.getEstado() != EstadoCita.COMPLETADA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo puedes calificar una cita que ya fue atendida");
        }
        if (resenaRepository.existsByCita_Id(citaId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya calificaste esta cita; puedes editar tu reseña");
        }
        Resena resena = resenaRepository.saveAndFlush(Resena.builder()
                .cita(cita)
                .odontologoId(cita.getOdontologo().getUsuarioId())
                .paciente(cita.getPaciente())
                .calificacion(validarCalificacion(req.calificacion()))
                .comentario(limpiar(req.comentario()))
                .build());
        return aRespuesta(resena, citaId);
    }

    @Transactional
    public ResenaResponse editar(UUID pacienteId, UUID citaId, ResenaRequest req) {
        Resena resena = resenaRepository.findByCita_IdAndPaciente_Id(citaId, pacienteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Todavía no calificaste esta cita"));
        resena.setCalificacion(validarCalificacion(req.calificacion()));
        resena.setComentario(limpiar(req.comentario()));
        return aRespuesta(resenaRepository.saveAndFlush(resena), citaId);
    }

    @Transactional(readOnly = true)
    public ResenasOdontologoResponse deOdontologo(UUID odontologoId, int pagina, int tamano) {
        if (pagina < 0 || tamano < 1 || tamano > TAMANO_MAXIMO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pagina desde 0 y tamano entre 1 y " + TAMANO_MAXIMO);
        }
        var perfil = perfilRepository.findById(odontologoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Odontólogo no encontrado"));

        Map<Integer, Long> distribucion = new LinkedHashMap<>();
        for (int estrellas = 5; estrellas >= 1; estrellas--) {
            distribucion.put(estrellas, 0L);
        }
        long total = 0;
        for (Object[] fila : resenaRepository.distribucion(odontologoId)) {
            int estrellas = ((Number) fila[0]).intValue();
            long cantidad = ((Number) fila[1]).longValue();
            distribucion.put(estrellas, cantidad);
            total += cantidad;
        }
        Page<Resena> resenas = resenaRepository.delOdontologo(odontologoId, PageRequest.of(pagina, tamano));
        BigDecimal promedio = perfil.getCalificacionPromedio() == null ? BigDecimal.ZERO
                : perfil.getCalificacionPromedio().setScale(2, RoundingMode.HALF_UP);
        return new ResenasOdontologoResponse(odontologoId, promedio, total, distribucion,
                resenas.getContent().stream().map(ResenaService::aPublica).toList(),
                pagina, tamano, resenas.getTotalPages());
    }

    /** Citas atendidas del paciente que aún no tienen reseña (para mostrarle "¿Cómo te fue?"). */
    @Transactional(readOnly = true)
    public List<CitaPorResenar> porResenar(UUID pacienteId) {
        return citaRepository.completadasSinResena(pacienteId).stream()
                .map(c -> new CitaPorResenar(c.getId(), c.getOdontologo().getUsuarioId(),
                        c.getOdontologo().getUsuario().getNombreCompleto(), c.getServicio().getTitulo(),
                        c.getInicioProgramado()))
                .toList();
    }

    // ------------------------------------------------------------------

    private static short validarCalificacion(Short calificacion) {
        if (calificacion == null || calificacion < 1 || calificacion > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La calificación va de 1 a 5");
        }
        return calificacion;
    }

    /** Sin espacios de más; un comentario vacío se guarda como "sin comentario". */
    static String limpiar(String comentario) {
        if (comentario == null) return null;
        String limpio = comentario.strip().replaceAll("\\s{3,}", "  ");
        return limpio.isEmpty() ? null : limpio;
    }

    /** "Lucía Ramos Peña" → "Lucía R.": se reconoce que es una persona real sin exponer su nombre completo. */
    static String nombreAbreviado(String nombreCompleto) {
        if (nombreCompleto == null || nombreCompleto.isBlank()) return "Paciente";
        String[] partes = nombreCompleto.strip().split("\\s+");
        return partes.length == 1 ? partes[0] : partes[0] + " " + partes[1].charAt(0) + ".";
    }

    private static ResenaResponse aRespuesta(Resena r, UUID citaId) {
        return new ResenaResponse(r.getId(), citaId, r.getOdontologoId(), r.getCalificacion(), r.getComentario(),
                r.getCreadoEn());
    }

    private static ResenaPublica aPublica(Resena r) {
        return new ResenaPublica(r.getId(), nombreAbreviado(r.getPaciente().getNombreCompleto()), r.getCalificacion(),
                r.getComentario(), r.getCreadoEn());
    }
}

package com.odontosystem.OdontoSystem.odontologos;

import com.odontosystem.OdontoSystem.entity.BloqueoAgenda;
import com.odontosystem.OdontoSystem.entity.CategoriaServicio;
import com.odontosystem.OdontoSystem.entity.HorarioAtencion;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.BloqueoRequest;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.BloqueoResponse;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.HorarioDto;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ServicioRequest;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ServicioResponse;
import com.odontosystem.OdontoSystem.repository.BloqueoAgendaRepository;
import com.odontosystem.OdontoSystem.repository.CategoriaServicioRepository;
import com.odontosystem.OdontoSystem.repository.HorarioAtencionRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ServicioRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Lo que el odontólogo administra de su consultorio: servicios, horario semanal y bloqueos de agenda. */
@Service
@RequiredArgsConstructor
public class MiConsultorioService {

    static final Duration BLOQUEO_MAXIMO = Duration.ofDays(60);

    private final PerfilOdontologoRepository perfilRepository;
    private final ServicioRepository servicioRepository;
    private final CategoriaServicioRepository categoriaRepository;
    private final HorarioAtencionRepository horarioRepository;
    private final BloqueoAgendaRepository bloqueoRepository;
    private final EntityManager entityManager;

    // ------------------------------------------------------------------
    // Servicios (RF04)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ServicioResponse> listarServicios(UUID odontologoId) {
        return servicioRepository.findByOdontologo_UsuarioIdOrderByCreadoEnAsc(odontologoId).stream()
                .map(PerfilOdontologoService::aServicio).toList();
    }

    @Transactional
    public ServicioResponse crearServicio(UUID odontologoId, ServicioRequest req) {
        PerfilOdontologo perfil = exigirPerfil(odontologoId);
        Servicio servicio = Servicio.builder()
                .odontologo(perfil)
                .categoria(exigirCategoria(req.categoriaId()))
                .titulo(req.titulo().trim())
                .descripcion(Texto.limpiar(req.descripcion()))
                .precioTotal(req.precioTotal())
                .duracionMinutos(req.duracionMinutos())
                .build();
        return guardarYRecargar(servicio);
    }

    @Transactional
    public ServicioResponse actualizarServicio(UUID odontologoId, UUID servicioId, ServicioRequest req) {
        Servicio servicio = exigirServicio(odontologoId, servicioId);
        servicio.setCategoria(exigirCategoria(req.categoriaId()));
        servicio.setTitulo(req.titulo().trim());
        servicio.setDescripcion(Texto.limpiar(req.descripcion()));
        servicio.setPrecioTotal(req.precioTotal());
        servicio.setDuracionMinutos(req.duracionMinutos());
        servicio.setEstaActivo(true);
        return guardarYRecargar(servicio);
    }

    /** Baja lógica: las citas pasadas siguen apuntando al servicio, así que no se borra. */
    @Transactional
    public void desactivarServicio(UUID odontologoId, UUID servicioId) {
        Servicio servicio = exigirServicio(odontologoId, servicioId);
        servicio.setEstaActivo(false);
        servicioRepository.save(servicio);
    }

    /** El depósito lo calcula un trigger de la BD (20 % configurable): se recarga para devolver el valor real. */
    private ServicioResponse guardarYRecargar(Servicio servicio) {
        Servicio guardado = servicioRepository.saveAndFlush(servicio);
        entityManager.refresh(guardado);
        return PerfilOdontologoService.aServicio(guardado);
    }

    // ------------------------------------------------------------------
    // Horario semanal (RF06)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<HorarioDto> listarHorarios(UUID odontologoId) {
        return horarioRepository.findByOdontologo_UsuarioIdOrderByDiaSemanaAscHoraInicioAsc(odontologoId).stream()
                .map(PerfilOdontologoService::aHorario).toList();
    }

    /** Reemplaza el horario completo: es lo que hace la pantalla "Mi horario" al guardar. */
    @Transactional
    public List<HorarioDto> reemplazarHorarios(UUID odontologoId, List<HorarioDto> horarios) {
        PerfilOdontologo perfil = exigirPerfil(odontologoId);
        validarHorarios(horarios);

        horarioRepository.deleteByOdontologo_UsuarioId(odontologoId);
        horarioRepository.flush();
        List<HorarioAtencion> nuevos = horarios.stream()
                .map(h -> HorarioAtencion.builder()
                        .odontologo(perfil)
                        .diaSemana(h.diaSemana())
                        .horaInicio(h.horaInicio())
                        .horaFin(h.horaFin())
                        .intervaloMinutos(h.intervaloMinutos() == null ? (short) 30 : h.intervaloMinutos())
                        .build())
                .toList();
        horarioRepository.saveAll(nuevos);
        return listarHorarios(odontologoId);
    }

    static void validarHorarios(List<HorarioDto> horarios) {
        for (HorarioDto h : horarios) {
            if (!h.horaFin().isAfter(h.horaInicio())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "En " + h.diaSemana() + " la hora de fin debe ser posterior a la de inicio");
            }
        }
        List<HorarioDto> ordenados = new ArrayList<>(horarios);
        ordenados.sort(Comparator.comparing(HorarioDto::diaSemana).thenComparing(HorarioDto::horaInicio));
        for (int i = 1; i < ordenados.size(); i++) {
            HorarioDto anterior = ordenados.get(i - 1);
            HorarioDto actual = ordenados.get(i);
            if (anterior.diaSemana() == actual.diaSemana() && actual.horaInicio().isBefore(anterior.horaFin())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "En " + actual.diaSemana() + " hay dos tramos que se cruzan");
            }
        }
    }

    // ------------------------------------------------------------------
    // Bloqueos de agenda (RF07)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<BloqueoResponse> listarBloqueos(UUID odontologoId) {
        return bloqueoRepository.findByOdontologo_UsuarioIdAndFinAfterOrderByInicioAsc(odontologoId, OffsetDateTime.now())
                .stream().map(MiConsultorioService::aBloqueo).toList();
    }

    @Transactional
    public BloqueoResponse crearBloqueo(UUID odontologoId, BloqueoRequest req) {
        PerfilOdontologo perfil = exigirPerfil(odontologoId);
        if (!req.fin().isAfter(req.inicio())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El fin del bloqueo debe ser posterior al inicio");
        }
        if (!req.fin().isAfter(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El bloqueo ya terminó");
        }
        if (Duration.between(req.inicio(), req.fin()).compareTo(BLOQUEO_MAXIMO) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un bloqueo puede durar como máximo 60 días");
        }
        BloqueoAgenda bloqueo = bloqueoRepository.save(BloqueoAgenda.builder()
                .odontologo(perfil)
                .inicio(req.inicio())
                .fin(req.fin())
                .motivo(Texto.limpiar(req.motivo()))
                .build());
        return aBloqueo(bloqueo);
    }

    @Transactional
    public void eliminarBloqueo(UUID odontologoId, UUID bloqueoId) {
        BloqueoAgenda bloqueo = bloqueoRepository.findByIdAndOdontologo_UsuarioId(bloqueoId, odontologoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bloqueo no encontrado"));
        bloqueoRepository.delete(bloqueo);
    }

    // ------------------------------------------------------------------

    private PerfilOdontologo exigirPerfil(UUID odontologoId) {
        return perfilRepository.findById(odontologoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Perfil de odontólogo no encontrado"));
    }

    private Servicio exigirServicio(UUID odontologoId, UUID servicioId) {
        return servicioRepository.findByIdAndOdontologo_UsuarioId(servicioId, odontologoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Servicio no encontrado"));
    }

    private CategoriaServicio exigirCategoria(Short categoriaId) {
        return categoriaRepository.findById(categoriaId)
                .filter(CategoriaServicio::isEstaActivo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La categoría no existe; consulta /api/catalogos/categorias-servicio"));
    }

    private static BloqueoResponse aBloqueo(BloqueoAgenda b) {
        return new BloqueoResponse(b.getId(), b.getInicio(), b.getFin(), b.getMotivo());
    }
}

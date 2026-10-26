package com.odontosystem.OdontoSystem.odontologos;

import com.odontosystem.OdontoSystem.archivos.ArchivosResponses.FotoConsultorioResponse;
import com.odontosystem.OdontoSystem.entity.Especialidad;
import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.HorarioAtencion;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ActualizarPerfilRequest;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.HorarioDto;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ItemCatalogo;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.MiPerfilResponse;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.PerfilPublicoResponse;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ServicioResponse;
import com.odontosystem.OdontoSystem.repository.EspecialidadRepository;
import com.odontosystem.OdontoSystem.repository.FotoConsultorioRepository;
import com.odontosystem.OdontoSystem.repository.HorarioAtencionRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ServicioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Perfil público del odontólogo (lo ve cualquiera) y el perfil propio que él mismo edita. */
@Service
@RequiredArgsConstructor
public class PerfilOdontologoService {

    private final PerfilOdontologoRepository perfilRepository;
    private final EspecialidadRepository especialidadRepository;
    private final ServicioRepository servicioRepository;
    private final HorarioAtencionRepository horarioRepository;
    private final FotoConsultorioRepository fotoRepository;

    /** Solo perfiles verificados y activos; cualquier otro caso responde 404 (no revela que existe). */
    @Transactional(readOnly = true)
    public PerfilPublicoResponse perfilPublico(UUID odontologoId) {
        PerfilOdontologo perfil = perfilRepository.findById(odontologoId)
                .filter(PerfilOdontologoService::esPublicable)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Odontólogo no encontrado"));
        return armarPerfil(perfil, servicioRepository.findByOdontologo_UsuarioIdAndEstaActivoTrue(odontologoId));
    }

    @Transactional(readOnly = true)
    public MiPerfilResponse miPerfil(UUID odontologoId) {
        return aMiPerfil(exigirPerfil(odontologoId));
    }

    @Transactional
    public MiPerfilResponse actualizarMiPerfil(UUID odontologoId, ActualizarPerfilRequest req) {
        PerfilOdontologo perfil = exigirPerfil(odontologoId);

        if ((req.latitud() == null) != (req.longitud() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envía latitud y longitud juntas");
        }
        if (req.nombreConsultorio() != null) perfil.setNombreConsultorio(Texto.limpiar(req.nombreConsultorio()));
        if (req.direccionConsultorio() != null) perfil.setDireccionConsultorio(Texto.limpiar(req.direccionConsultorio()));
        if (req.distritoConsultorio() != null) perfil.setDistritoConsultorio(Texto.limpiar(req.distritoConsultorio()));
        if (req.latitud() != null) {
            perfil.setLatitudConsultorio(req.latitud());
            perfil.setLongitudConsultorio(req.longitud());
        }
        if (req.requiereConfirmacionManual() != null) perfil.setRequiereConfirmacionManual(req.requiereConfirmacionManual());
        if (req.frecuenciaDisponibilidad() != null) perfil.setFrecuenciaDisponibilidad(req.frecuenciaDisponibilidad());
        if (req.ruc() != null) perfil.setRuc(Texto.limpiar(req.ruc()));
        if (req.cci() != null) perfil.setCci(Texto.limpiar(req.cci()));
        if (req.especialidadIds() != null) {
            var ids = new HashSet<>(req.especialidadIds());
            List<Especialidad> especialidades = especialidadRepository.findAllById(ids);
            if (especialidades.size() != ids.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Alguna especialidad no existe; consulta /api/catalogos/especialidades");
            }
            perfil.getEspecialidades().clear();
            perfil.getEspecialidades().addAll(especialidades);
        }
        perfilRepository.save(perfil);
        return aMiPerfil(perfil);
    }

    // ------------------------------------------------------------------

    PerfilOdontologo exigirPerfil(UUID odontologoId) {
        return perfilRepository.findById(odontologoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Perfil de odontólogo no encontrado"));
    }

    static boolean esPublicable(PerfilOdontologo p) {
        return p.getDesactivadoEn() == null
                && p.getEstadoVerificacion() == EstadoVerificacionOdontologo.VERIFICADO
                && Boolean.TRUE.equals(p.getUsuario().getEstaActivo());
    }

    private MiPerfilResponse aMiPerfil(PerfilOdontologo perfil) {
        // El odontólogo ve también sus servicios desactivados
        List<Servicio> servicios = servicioRepository.findByOdontologo_UsuarioIdOrderByCreadoEnAsc(perfil.getUsuarioId());
        return new MiPerfilResponse(armarPerfil(perfil, servicios), perfil.getEstadoVerificacion(),
                perfil.getNotaObservacion(), perfil.getRuc(), perfil.getCci(),
                perfil.getFrecuenciaDisponibilidad(), perfil.getDisponibilidadActualizadaEn());
    }

    private PerfilPublicoResponse armarPerfil(PerfilOdontologo p, List<Servicio> servicios) {
        UUID id = p.getUsuarioId();
        List<ItemCatalogo> especialidades = p.getEspecialidades().stream()
                .sorted(Comparator.comparing(Especialidad::getNombre))
                .map(e -> new ItemCatalogo(e.getId(), e.getNombre())).toList();
        List<HorarioDto> horarios = horarioRepository.findByOdontologo_UsuarioIdOrderByDiaSemanaAscHoraInicioAsc(id).stream()
                .map(PerfilOdontologoService::aHorario).toList();
        List<FotoConsultorioResponse> fotos = fotoRepository.findByOdontologoIdOrderByOrdenAsc(id).stream()
                .map(f -> new FotoConsultorioResponse(f.getId(), f.getUrlFoto(), f.getDescripcion(), f.getOrden())).toList();
        return new PerfilPublicoResponse(id, p.getUsuario().getNombreCompleto(), p.getUsuario().getFotoPerfilUrl(),
                p.getNombreConsultorio(), p.getDireccionConsultorio(), p.getDistritoConsultorio(),
                p.getLatitudConsultorio(), p.getLongitudConsultorio(), p.getNumeroColegiatura(), p.isColegiaturaVerificada(),
                p.getCalificacionPromedio(), p.getTotalResenas(), p.isRequiereConfirmacionManual(),
                especialidades, servicios.stream().map(PerfilOdontologoService::aServicio).toList(), horarios, fotos);
    }

    static ServicioResponse aServicio(Servicio s) {
        return new ServicioResponse(s.getId(), s.getCategoria().getId(), s.getCategoria().getNombre(), s.getTitulo(),
                s.getDescripcion(), s.getPrecioTotal(), s.getMontoDeposito(), s.getDuracionMinutos(), s.isEstaActivo());
    }

    static HorarioDto aHorario(HorarioAtencion h) {
        return new HorarioDto(h.getDiaSemana(), h.getHoraInicio(), h.getHoraFin(), h.getIntervaloMinutos());
    }
}

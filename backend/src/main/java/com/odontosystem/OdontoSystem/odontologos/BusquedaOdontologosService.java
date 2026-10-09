package com.odontosystem.OdontoSystem.odontologos;

import com.odontosystem.OdontoSystem.entity.Especialidad;
import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.FiltroBusqueda;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.OdontologoTarjeta;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.Pagina;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ServicioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Buscador de odontólogos (RF02). Solo muestra perfiles VERIFICADOS y activos.
 * Filtros: distrito, especialidad, categoría de servicio, texto libre y cercanía (lat/lng + radio).
 * <p>
 * Los perfiles se filtran en memoria: con el volumen del piloto (decenas de odontólogos en Ica)
 * es más simple y se puede probar sin base de datos. Si el volumen crece, el filtro de cercanía
 * pasa a una consulta PostGIS con ST_DWithin sobre el índice idx_perfiles_odontologo_ubicacion_geografica.
 */
@Service
@RequiredArgsConstructor
public class BusquedaOdontologosService {

    static final double RADIO_POR_DEFECTO_KM = 10;
    static final double RADIO_MAXIMO_KM = 100;
    static final int TAMANO_MAXIMO = 50;
    private static final double RADIO_TIERRA_KM = 6371.0;

    private final PerfilOdontologoRepository perfilRepository;
    private final ServicioRepository servicioRepository;

    @Transactional(readOnly = true)
    public Pagina<OdontologoTarjeta> buscar(FiltroBusqueda filtro) {
        validar(filtro);
        boolean porCercania = filtro.lat() != null;
        double radioKm = filtro.radioKm() == null ? RADIO_POR_DEFECTO_KM : filtro.radioKm();

        List<PerfilOdontologo> perfiles = perfilRepository.buscarPublicables(EstadoVerificacionOdontologo.VERIFICADO);
        List<UUID> ids = perfiles.stream().map(PerfilOdontologo::getUsuarioId).toList();
        Map<UUID, List<Servicio>> serviciosPorOdontologo = ids.isEmpty() ? Map.of()
                : servicioRepository.findByOdontologo_UsuarioIdInAndEstaActivoTrue(ids).stream()
                        .collect(Collectors.groupingBy(s -> s.getOdontologo().getUsuarioId()));

        String distrito = Texto.normalizar(filtro.distrito());
        String q = Texto.normalizar(filtro.q());

        List<OdontologoTarjeta> resultados = perfiles.stream()
                // Sin servicios activos no hay nada que reservar: el perfil no se muestra hasta que lo complete
                .filter(p -> !serviciosPorOdontologo.getOrDefault(p.getUsuarioId(), List.of()).isEmpty())
                .filter(p -> distrito.isEmpty() || distrito.equals(Texto.normalizar(p.getDistritoConsultorio())))
                .filter(p -> filtro.especialidadId() == null || p.getEspecialidades().stream()
                        .anyMatch(e -> e.getId().equals(filtro.especialidadId())))
                .filter(p -> filtro.categoriaId() == null || serviciosPorOdontologo.getOrDefault(p.getUsuarioId(), List.of())
                        .stream().anyMatch(s -> s.getCategoria().getId().equals(filtro.categoriaId())))
                .filter(p -> q.isEmpty() || coincideTexto(p, q))
                .map(p -> aTarjeta(p, serviciosPorOdontologo.getOrDefault(p.getUsuarioId(), List.of()),
                        porCercania ? distanciaKm(filtro.lat(), filtro.lng(), p) : null))
                .filter(t -> !porCercania || (t.distanciaKm() != null && t.distanciaKm() <= radioKm))
                .sorted(orden(porCercania, filtro.orden()))
                .toList();

        int tamano = filtro.tamano() <= 0 ? 20 : Math.min(filtro.tamano(), TAMANO_MAXIMO);
        int pagina = Math.max(filtro.pagina(), 0);
        int desde = Math.min(pagina * tamano, resultados.size());
        int hasta = Math.min(desde + tamano, resultados.size());
        int totalPaginas = (int) Math.ceil(resultados.size() / (double) tamano);
        return new Pagina<>(resultados.subList(desde, hasta), pagina, tamano, resultados.size(), totalPaginas);
    }

    private static void validar(FiltroBusqueda f) {
        if ((f.lat() == null) != (f.lng() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Para buscar por cercanía envía lat y lng juntos");
        }
        if (f.lat() != null && (Math.abs(f.lat()) > 90 || Math.abs(f.lng()) > 180)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coordenadas fuera de rango");
        }
        if (f.radioKm() != null && (f.radioKm() <= 0 || f.radioKm() > RADIO_MAXIMO_KM)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "radio_km debe estar entre 0 y " + (int) RADIO_MAXIMO_KM);
        }
        if (f.orden() != null && !List.of("calificacion", "precio", "distancia").contains(f.orden())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orden admite: calificacion, precio o distancia");
        }
    }

    private static boolean coincideTexto(PerfilOdontologo p, String q) {
        return Texto.normalizar(p.getUsuario().getNombreCompleto()).contains(q)
                || Texto.normalizar(p.getNombreConsultorio()).contains(q)
                || p.getEspecialidades().stream().anyMatch(e -> Texto.normalizar(e.getNombre()).contains(q));
    }

    static OdontologoTarjeta aTarjeta(PerfilOdontologo p, List<Servicio> servicios, Double distanciaKm) {
        BigDecimal precioDesde = servicios.stream().map(Servicio::getPrecioTotal)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        List<String> especialidades = p.getEspecialidades().stream()
                .map(Especialidad::getNombre).sorted().toList();
        return new OdontologoTarjeta(p.getUsuarioId(), p.getUsuario().getNombreCompleto(), p.getUsuario().getFotoPerfilUrl(),
                p.getNombreConsultorio(), p.getDistritoConsultorio(), p.getCalificacionPromedio(), p.getTotalResenas(),
                especialidades, precioDesde, distanciaKm == null ? null : Math.round(distanciaKm * 10) / 10.0);
    }

    private static Comparator<OdontologoTarjeta> orden(boolean porCercania, String orden) {
        Comparator<OdontologoTarjeta> porCalificacion = Comparator
                .comparing(OdontologoTarjeta::calificacion, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(OdontologoTarjeta::totalResenas, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(OdontologoTarjeta::nombre, Comparator.nullsLast(Comparator.naturalOrder()));
        String criterio = orden != null ? orden : (porCercania ? "distancia" : "calificacion");
        return switch (criterio) {
            case "precio" -> Comparator.comparing(OdontologoTarjeta::precioDesde, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(porCalificacion);
            case "distancia" -> Comparator.comparing(OdontologoTarjeta::distanciaKm, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(porCalificacion);
            default -> porCalificacion;
        };
    }

    /** Distancia en km (fórmula de Haversine); null si el consultorio no tiene coordenadas. */
    static Double distanciaKm(double lat, double lng, PerfilOdontologo p) {
        if (p.getLatitudConsultorio() == null || p.getLongitudConsultorio() == null) return null;
        double lat2 = p.getLatitudConsultorio().doubleValue();
        double lng2 = p.getLongitudConsultorio().doubleValue();
        double dLat = Math.toRadians(lat2 - lat);
        double dLng = Math.toRadians(lng2 - lng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return RADIO_TIERRA_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

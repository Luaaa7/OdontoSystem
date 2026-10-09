package com.odontosystem.OdontoSystem.odontologos;

import com.odontosystem.OdontoSystem.entity.CategoriaServicio;
import com.odontosystem.OdontoSystem.entity.Especialidad;
import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Servicio;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.FiltroBusqueda;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.OdontologoTarjeta;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.Pagina;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ServicioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Buscador en memoria: perfiles y servicios simulados, sin base de datos. */
@ExtendWith(MockitoExtension.class)
class BusquedaOdontologosServiceTest {

    @Mock private PerfilOdontologoRepository perfilRepository;
    @Mock private ServicioRepository servicioRepository;

    private BusquedaOdontologosService servicio;

    private static final Especialidad ORTODONCIA = new Especialidad((short) 2, "Ortodoncia");
    private static final Especialidad GENERAL = new Especialidad((short) 1, "Odontología General");
    private static final CategoriaServicio LIMPIEZA = new CategoriaServicio((short) 2, "Limpieza y prevención", true);
    private static final CategoriaServicio IMPLANTES = new CategoriaServicio((short) 8, "Implantes y prótesis", true);

    // Plaza de Armas de Ica ≈ (-14.0639, -75.7292)
    private final PerfilOdontologo ana = perfil("Ana Torres", "Clínica Sonrisa", "Ica Centro", -14.0640, -75.7290, "4.80", 12, GENERAL);
    private final PerfilOdontologo luis = perfil("Luis Pérez", "Dental Parcona", "Parcona", -14.0450, -75.6990, "4.50", 30, ORTODONCIA);
    private final PerfilOdontologo rosa = perfil("Rosa Díaz", "Consultorio Rosa", "La Tinguiña", -14.0330, -75.7080, "4.90", 3, ORTODONCIA, GENERAL);
    private final PerfilOdontologo pisco = perfil("Marco Ríos", "Pisco Dental", "Pisco", -13.7100, -76.2030, "5.00", 1, GENERAL);

    @BeforeEach
    void setUp() {
        servicio = new BusquedaOdontologosService(perfilRepository, servicioRepository);
        lenient().when(perfilRepository.buscarPublicables(EstadoVerificacionOdontologo.VERIFICADO)).thenReturn(List.of(ana, luis, rosa, pisco));
        lenient().when(servicioRepository.findByOdontologo_UsuarioIdInAndEstaActivoTrue(anyList())).thenReturn(List.of(
                servicio(ana, LIMPIEZA, "80.00"), servicio(ana, IMPLANTES, "1500.00"),
                servicio(luis, LIMPIEZA, "60.00"),
                servicio(rosa, IMPLANTES, "1200.00")));
    }

    private static FiltroBusqueda filtro(String distrito, Short esp, Short cat, String q, Double lat, Double lng, Double radio, String orden) {
        return new FiltroBusqueda(distrito, esp, cat, q, lat, lng, radio, orden, 0, 20);
    }

    private static List<String> nombres(Pagina<OdontologoTarjeta> p) {
        return p.contenido().stream().map(OdontologoTarjeta::nombre).toList();
    }

    @Test
    @DisplayName("Sin filtros: todos, ordenados por calificación")
    void sinFiltros() {
        var r = servicio.buscar(filtro(null, null, null, null, null, null, null, null));
        assertThat(nombres(r)).containsExactly("Marco Ríos", "Rosa Díaz", "Ana Torres", "Luis Pérez");
        assertThat(r.totalElementos()).isEqualTo(4);
    }

    @Test
    @DisplayName("Distrito sin importar tildes ni mayúsculas")
    void filtraDistrito() {
        var r = servicio.buscar(filtro("la tinguina", null, null, null, null, null, null, null));
        assertThat(nombres(r)).containsExactly("Rosa Díaz");
    }

    @Test
    @DisplayName("Especialidad y categoría de servicio")
    void filtraEspecialidadYCategoria() {
        assertThat(nombres(servicio.buscar(filtro(null, (short) 2, null, null, null, null, null, null))))
                .containsExactlyInAnyOrder("Luis Pérez", "Rosa Díaz");
        assertThat(nombres(servicio.buscar(filtro(null, null, (short) 8, null, null, null, null, null))))
                .containsExactlyInAnyOrder("Ana Torres", "Rosa Díaz");
    }

    @Test
    @DisplayName("Texto libre busca en nombre, consultorio y especialidad")
    void textoLibre() {
        assertThat(nombres(servicio.buscar(filtro(null, null, null, "sonrisa", null, null, null, null)))).containsExactly("Ana Torres");
        assertThat(nombres(servicio.buscar(filtro(null, null, null, "PEREZ", null, null, null, null)))).containsExactly("Luis Pérez");
    }

    @Test
    @DisplayName("Cerca de mí: excluye los que están fuera del radio y ordena por distancia")
    void cercania() {
        var r = servicio.buscar(filtro(null, null, null, null, -14.0639, -75.7292, 10.0, null));
        assertThat(nombres(r)).containsExactly("Ana Torres", "Luis Pérez", "Rosa Díaz");
        assertThat(r.contenido().get(0).distanciaKm()).isLessThan(0.1);
        assertThat(r.contenido().get(2).distanciaKm()).isBetween(3.0, 5.0);
    }

    @Test
    @DisplayName("Precio desde = el servicio activo más barato; orden por precio")
    void precioDesde() {
        var r = servicio.buscar(filtro(null, null, null, null, null, null, null, "precio"));
        assertThat(nombres(r).subList(0, 3)).containsExactly("Luis Pérez", "Ana Torres", "Rosa Díaz");
        assertThat(r.contenido().get(1).precioDesde()).isEqualByComparingTo("80.00");
        assertThat(r.contenido().get(3).precioDesde()).isNull(); // Marco no tiene servicios
    }

    @Test
    @DisplayName("Paginación")
    void paginacion() {
        var r = servicio.buscar(new FiltroBusqueda(null, null, null, null, null, null, null, null, 1, 3));
        assertThat(r.contenido()).hasSize(1);
        assertThat(r.totalPaginas()).isEqualTo(2);
    }

    @Test
    @DisplayName("lat sin lng u orden desconocido → 400")
    void validaFiltros() {
        assertThatThrownBy(() -> servicio.buscar(filtro(null, null, null, null, -14.0, null, null, null)))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> servicio.buscar(filtro(null, null, null, null, null, null, null, "edad")))
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ------------------------------------------------------------------

    private static PerfilOdontologo perfil(String nombre, String consultorio, String distrito, double lat, double lng,
                                           String calificacion, int resenas, Especialidad... especialidades) {
        UUID id = UUID.randomUUID();
        Usuario usuario = Usuario.builder().id(id).nombreCompleto(nombre).build();
        return PerfilOdontologo.builder()
                .usuarioId(id).usuario(usuario)
                .nombreConsultorio(consultorio).distritoConsultorio(distrito)
                .latitudConsultorio(BigDecimal.valueOf(lat)).longitudConsultorio(BigDecimal.valueOf(lng))
                .calificacionPromedio(new BigDecimal(calificacion)).totalResenas(resenas)
                .especialidades(new HashSet<>(Set.of(especialidades)))
                .build();
    }

    private static Servicio servicio(PerfilOdontologo p, CategoriaServicio categoria, String precio) {
        return Servicio.builder().id(UUID.randomUUID()).odontologo(p).categoria(categoria)
                .titulo("Servicio").precioTotal(new BigDecimal(precio)).build();
    }
}

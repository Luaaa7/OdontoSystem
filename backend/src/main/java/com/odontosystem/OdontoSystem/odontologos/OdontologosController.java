package com.odontosystem.OdontoSystem.odontologos;

import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.FiltroBusqueda;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.OdontologoTarjeta;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.Pagina;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.PerfilPublicoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Buscador y perfil público. No requieren login: un paciente puede explorar antes de registrarse. */
@Tag(name = "Odontólogos", description = "Buscador y perfil público (sin login)")
@RestController
@RequestMapping("/api/odontologos")
@RequiredArgsConstructor
public class OdontologosController {

    private final BusquedaOdontologosService busquedaService;
    private final PerfilOdontologoService perfilService;

    @Operation(summary = "Buscar odontólogos verificados",
            description = "Filtros opcionales: distrito (sin importar tildes), especialidad_id, categoria_id (tipo de servicio), "
                    + "q (nombre, consultorio o especialidad) y cercanía con lat + lng (+ radio_km, por defecto 10). "
                    + "orden: calificacion (por defecto), precio o distancia (por defecto si hay lat/lng). Página desde 0.")
    @GetMapping
    public Pagina<OdontologoTarjeta> buscar(@RequestParam(required = false) String distrito,
                                            @RequestParam(name = "especialidad_id", required = false) Short especialidadId,
                                            @RequestParam(name = "categoria_id", required = false) Short categoriaId,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) Double lat,
                                            @RequestParam(required = false) Double lng,
                                            @RequestParam(name = "radio_km", required = false) Double radioKm,
                                            @RequestParam(required = false) String orden,
                                            @RequestParam(defaultValue = "0") int pagina,
                                            @RequestParam(defaultValue = "20") int tamano) {
        return busquedaService.buscar(new FiltroBusqueda(distrito, especialidadId, categoriaId, q, lat, lng, radioKm,
                orden, pagina, tamano));
    }

    @Operation(summary = "Perfil público de un odontólogo",
            description = "Datos del consultorio, especialidades, servicios activos con precio y depósito, horario semanal y fotos.")
    @GetMapping("/{id}")
    public PerfilPublicoResponse perfil(@PathVariable UUID id) {
        return perfilService.perfilPublico(id);
    }
}

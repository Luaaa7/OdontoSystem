package com.odontosystem.OdontoSystem.odontologos;

import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.ItemCatalogo;
import com.odontosystem.OdontoSystem.repository.CategoriaServicioRepository;
import com.odontosystem.OdontoSystem.repository.EspecialidadRepository;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/** Listas para los filtros y formularios de los frontends. Públicas. */
@Tag(name = "Catálogos", description = "Especialidades, categorías de servicio y distritos con odontólogos")
@RestController
@RequestMapping("/api/catalogos")
@RequiredArgsConstructor
public class CatalogosController {

    private final EspecialidadRepository especialidadRepository;
    private final CategoriaServicioRepository categoriaRepository;
    private final PerfilOdontologoRepository perfilRepository;

    @Operation(summary = "Especialidades")
    @GetMapping("/especialidades")
    public List<ItemCatalogo> especialidades() {
        return especialidadRepository.findAllByOrderByNombreAsc().stream()
                .map(e -> new ItemCatalogo(e.getId(), e.getNombre())).toList();
    }

    @Operation(summary = "Categorías de servicio (tipo de tratamiento)")
    @GetMapping("/categorias-servicio")
    public List<ItemCatalogo> categorias() {
        return categoriaRepository.findByEstaActivoTrueOrderByIdAsc().stream()
                .map(c -> new ItemCatalogo(c.getId(), c.getNombre())).toList();
    }

    @Operation(summary = "Distritos con al menos un odontólogo verificado", description = "Para el filtro del buscador.")
    @GetMapping("/distritos")
    @Transactional(readOnly = true)
    public List<String> distritos() {
        return perfilRepository.buscarPublicables(EstadoVerificacionOdontologo.VERIFICADO).stream()
                .map(PerfilOdontologo::getDistritoConsultorio)
                .filter(Objects::nonNull)
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}

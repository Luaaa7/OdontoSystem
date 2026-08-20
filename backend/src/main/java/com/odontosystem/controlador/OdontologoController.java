package com.odontosystem.controlador;

import com.odontosystem.entidad.Odontologo;
import com.odontosystem.servicio.OdontologoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/odontologos")
@RequiredArgsConstructor
public class OdontologoController {

    private final OdontologoService odontologoService;

    // GET /api/odontologos?distrito=Ica
    @GetMapping
    public List<Odontologo> buscar(@RequestParam(required = false) String distrito) {
        if (distrito == null || distrito.isBlank()) {
            return odontologoService.listarTodos();
        }
        return odontologoService.buscarPorDistrito(distrito);
    }

    @GetMapping("/{id}")
    public Odontologo obtener(@PathVariable Long id) {
        return odontologoService.obtenerPorId(id);
    }
}

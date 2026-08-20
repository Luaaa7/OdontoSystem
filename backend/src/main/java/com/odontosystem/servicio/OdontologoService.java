package com.odontosystem.servicio;

import com.odontosystem.entidad.Odontologo;
import com.odontosystem.repositorio.OdontologoRepositorio;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OdontologoService {

    private final OdontologoRepositorio odontologoRepositorio;

    /**
     * Busca odontologos con perfil aprobado en un distrito especifico.
     * Este metodo lo usa tanto el controlador REST del marketplace
     * como el intent BUSCAR_ODONTOLOGO del chatbot.
     */
    public List<Odontologo> buscarPorDistrito(String nombreDistrito) {
        return odontologoRepositorio.findByDistrito_NombreIgnoreCaseAndPerfilCompletoTrue(nombreDistrito);
    }

    public List<Odontologo> listarTodos() {
        return odontologoRepositorio.findByPerfilCompletoTrue();
    }

    public Odontologo obtenerPorId(Long id) {
        return odontologoRepositorio.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Odontologo no encontrado: " + id));
    }
}

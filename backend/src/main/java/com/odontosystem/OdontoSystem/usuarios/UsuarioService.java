package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Datos de la cuenta del usuario autenticado (paciente u odontólogo). */
@Service
@RequiredArgsConstructor
public class UsuarioService {

    public record UsuarioResponse(UUID id, String nombreCompleto, String correo, String telefono,
                                  String tipoDocumento, String numeroDocumento, RolUsuario rol, String fotoPerfilUrl) {}

    /** Correo y documento no se cambian aquí: identifican la cuenta. */
    public record ActualizarUsuarioRequest(@Size(min = 3, max = 150) String nombreCompleto,
                                           @Pattern(regexp = "\\+?\\d{9,15}", message = "Teléfono inválido") String telefono) {}

    private final UsuarioRepository usuarioRepository;

    @Transactional(readOnly = true)
    public UsuarioResponse obtener(UUID usuarioId) {
        return aRespuesta(exigir(usuarioId));
    }

    @Transactional
    public UsuarioResponse actualizar(UUID usuarioId, ActualizarUsuarioRequest req) {
        Usuario usuario = exigir(usuarioId);
        if (req.nombreCompleto() != null && !req.nombreCompleto().isBlank()) {
            usuario.setNombreCompleto(req.nombreCompleto().trim());
        }
        if (req.telefono() != null && !req.telefono().equals(usuario.getTelefono())) {
            if (usuarioRepository.existsByTelefono(req.telefono())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese teléfono ya está registrado en otra cuenta");
            }
            usuario.setTelefono(req.telefono());
        }
        usuarioRepository.save(usuario);
        return aRespuesta(usuario);
    }

    private Usuario exigir(UUID usuarioId) {
        return usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
    }

    static UsuarioResponse aRespuesta(Usuario u) {
        return new UsuarioResponse(u.getId(), u.getNombreCompleto(), u.getCorreo(), u.getTelefono(),
                u.getTipoDocumento(), u.getNumeroDocumento(), u.getRol(), u.getFotoPerfilUrl());
    }
}

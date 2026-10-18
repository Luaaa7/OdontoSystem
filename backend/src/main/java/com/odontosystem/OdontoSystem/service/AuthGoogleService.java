package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.dto.AuthResponse;
import com.odontosystem.OdontoSystem.dto.GoogleRegistroRequest;
import com.odontosystem.OdontoSystem.entity.MetodoAutenticacion;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import com.odontosystem.OdontoSystem.security.VerificadorGoogle;
import com.odontosystem.OdontoSystem.security.VerificadorGoogle.DatosGoogle;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * RF16: entrar y registrarse con Google.
 * <ul>
 *   <li><b>Entrar</b>: si la cuenta de Google ya está ligada, entra. Si no, pero existe una cuenta con el mismo
 *       correo (y Google confirma que el correo es suyo), se liga y entra. Si no hay cuenta, responde 404 para que
 *       el frontend muestre el formulario de registro.</li>
 *   <li><b>Registrarse</b>: correo y nombre vienen de Google; DNI, teléfono, rol y COP los completa la persona.
 *       La cuenta no tiene contraseña (puede crear una con "olvidé mi contraseña").</li>
 * </ul>
 */
@Service
public class AuthGoogleService {

    private final VerificadorGoogle verificador;
    private final UsuarioRepository usuarioRepository;
    private final AuthService authService;

    public AuthGoogleService(VerificadorGoogle verificador, UsuarioRepository usuarioRepository, AuthService authService) {
        this.verificador = verificador;
        this.usuarioRepository = usuarioRepository;
        this.authService = authService;
    }

    @Transactional
    public AuthResponse entrar(String idToken, HttpServletRequest httpRequest) {
        DatosGoogle google = verificador.verificar(idToken);

        Usuario usuario = usuarioRepository.findByGoogleOauthSub(google.sub())
                .or(() -> ligarPorCorreo(google))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No hay una cuenta con este Google. Completa tu registro."));

        if (!Boolean.TRUE.equals(usuario.getEstaActivo())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tu cuenta está desactivada");
        }
        return authService.emitirTokens(usuario, httpRequest);
    }

    @Transactional
    public AuthResponse registrar(GoogleRegistroRequest request, HttpServletRequest httpRequest) {
        DatosGoogle google = verificador.verificar(request.idToken());
        if (!google.correoVerificado() || google.correo() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Google no confirma tu correo; regístrate con correo y contraseña");
        }
        if (usuarioRepository.findByGoogleOauthSub(google.sub()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta cuenta de Google ya está registrada; inicia sesión");
        }

        String nombre = textoONulo(request.nombreCompleto()) != null ? request.nombreCompleto().strip() : textoONulo(google.nombre());
        if (nombre == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe tu nombre completo");
        }

        authService.validarDatosNuevos(google.correo(), request.numeroDocumento(), request.telefono(), request.rol(),
                request.numeroColegiatura());

        Usuario nuevo = Usuario.builder()
                .numeroDocumento(request.numeroDocumento())
                .tipoDocumento(request.tipoDocumento())
                .nombreCompleto(nombre)
                .telefono(request.telefono())
                .correo(google.correo())
                .googleOauthSub(google.sub())
                .fotoPerfilUrl(google.foto())
                .emailVerificadoEn(OffsetDateTime.now())
                .rol(request.rol())
                .metodoRegistro(MetodoAutenticacion.GOOGLE_OAUTH)
                .build();

        return authService.crearCuenta(nuevo, request.numeroColegiatura(), httpRequest);
    }

    /** Liga la cuenta de Google a una cuenta existente con el mismo correo, solo si Google confirma que el correo es suyo. */
    private Optional<Usuario> ligarPorCorreo(DatosGoogle google) {
        if (!google.correoVerificado() || google.correo() == null) {
            return Optional.empty();
        }
        return usuarioRepository.findByCorreo(google.correo()).map(u -> {
            u.setGoogleOauthSub(google.sub());
            if (u.getEmailVerificadoEn() == null) {
                u.setEmailVerificadoEn(OffsetDateTime.now());
            }
            return usuarioRepository.save(u);
        });
    }

    private static String textoONulo(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

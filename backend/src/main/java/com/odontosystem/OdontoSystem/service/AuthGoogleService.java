package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.dto.AuthResponse;
import com.odontosystem.OdontoSystem.dto.GoogleAuthResponse;
import com.odontosystem.OdontoSystem.dto.GoogleLoginRequest;
import com.odontosystem.OdontoSystem.dto.GoogleRegistroRequest;
import com.odontosystem.OdontoSystem.entity.MetodoAutenticacion;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
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
 *   <li>Si la cuenta de Google ya está ligada, entra. Si no, pero existe una cuenta con el mismo correo (y Google
 *       confirma que el correo es suyo), se liga y entra.</li>
 *   <li>Si no hay cuenta: sin datos de registro responde perfil_completo = false (con correo y nombre de Google para
 *       prellenar); con los datos (rol, documento, teléfono, COP, privacidad) crea la cuenta y entra.</li>
 *   <li>La cuenta creada con Google no tiene contraseña (puede crear una con "olvidé mi contraseña").</li>
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

    /** POST /api/auth/google: entra, pide completar datos o registra, según el caso. */
    @Transactional
    public GoogleAuthResponse continuar(GoogleLoginRequest request, HttpServletRequest httpRequest) {
        DatosGoogle google = verificador.verificar(request.idToken());

        Optional<Usuario> existente = usuarioRepository.findByGoogleOauthSub(google.sub()).or(() -> ligarPorCorreo(google));
        if (existente.isPresent()) {
            Usuario usuario = existente.get();
            if (!Boolean.TRUE.equals(usuario.getEstaActivo())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tu cuenta está desactivada");
            }
            return GoogleAuthResponse.sesion(authService.emitirTokens(usuario, httpRequest), usuario.getCorreo());
        }

        exigirCorreoVerificado(google);
        if (!request.traeDatosDeRegistro()) {
            return GoogleAuthResponse.faltanDatos(google.correo(), textoONulo(google.nombre()));
        }
        if (request.tipoDocumento() == null || vacio(request.numeroDocumento()) || vacio(request.telefono())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Para crear tu cuenta completa tipo y número de documento y teléfono");
        }
        if (!Boolean.TRUE.equals(request.aceptaPrivacidad())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes aceptar la política de privacidad para registrarte");
        }
        AuthResponse tokens = crearCuenta(google, request.rol(), request.tipoDocumento(), request.numeroDocumento().strip(),
                request.telefono().strip(), request.numeroColegiatura(), request.nombreCompleto(), httpRequest);
        return GoogleAuthResponse.sesion(tokens, google.correo());
    }

    /** POST /api/auth/google/registro (se mantiene por compatibilidad: hace lo mismo que /google con datos). */
    @Transactional
    public AuthResponse registrar(GoogleRegistroRequest request, HttpServletRequest httpRequest) {
        DatosGoogle google = verificador.verificar(request.idToken());
        exigirCorreoVerificado(google);
        if (usuarioRepository.findByGoogleOauthSub(google.sub()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta cuenta de Google ya está registrada; inicia sesión");
        }
        return crearCuenta(google, request.rol(), request.tipoDocumento(), request.numeroDocumento(), request.telefono(),
                request.numeroColegiatura(), request.nombreCompleto(), httpRequest);
    }

    // ------------------------------------------------------------------

    private AuthResponse crearCuenta(DatosGoogle google, RolUsuario rol, String tipoDocumento, String numeroDocumento,
                                     String telefono, String numeroColegiatura, String nombreEscrito,
                                     HttpServletRequest httpRequest) {
        String nombre = textoONulo(nombreEscrito) != null ? nombreEscrito.strip() : textoONulo(google.nombre());
        if (nombre == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe tu nombre completo");
        }
        authService.validarDatosNuevos(google.correo(), numeroDocumento, telefono, rol, numeroColegiatura);

        Usuario nuevo = Usuario.builder()
                .numeroDocumento(numeroDocumento)
                .tipoDocumento(tipoDocumento)
                .nombreCompleto(nombre)
                .telefono(telefono)
                .correo(google.correo())
                .googleOauthSub(google.sub())
                .fotoPerfilUrl(google.foto())
                .emailVerificadoEn(OffsetDateTime.now())
                .rol(rol)
                .metodoRegistro(MetodoAutenticacion.GOOGLE_OAUTH)
                .build();

        return authService.crearCuenta(nuevo, numeroColegiatura, httpRequest);
    }

    private static void exigirCorreoVerificado(DatosGoogle google) {
        if (!google.correoVerificado() || google.correo() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Google no confirma tu correo; regístrate con correo y contraseña");
        }
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

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }

    private static String textoONulo(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

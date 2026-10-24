package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.dto.AuthResponse;
import com.odontosystem.OdontoSystem.dto.LoginRequest;
import com.odontosystem.OdontoSystem.dto.RegistroRequest;
import com.odontosystem.OdontoSystem.entity.*;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ReputacionPacienteRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import com.odontosystem.OdontoSystem.security.JwtService;
import com.odontosystem.OdontoSystem.security.LimiteLoginService;
import com.odontosystem.OdontoSystem.security.LoginBloqueadoException;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import com.odontosystem.OdontoSystem.usuarios.ConsentimientoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;


@Service
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final TokenRefrescoService tokenRefrescoService;
    private final PerfilOdontologoRepository perfilOdontologoRepository;
    private final ReputacionPacienteRepository reputacionPacienteRepository;
    private final VerificacionColegiaturaService verificacionColegiatura;
    private final LimiteLoginService limiteLogin;
    private final ConsentimientoService consentimientos;

    public AuthService(
            UsuarioRepository usuarioRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            TokenRefrescoService tokenRefrescoService,
            PerfilOdontologoRepository perfilOdontologoRepository,
            ReputacionPacienteRepository reputacionPacienteRepository,
            VerificacionColegiaturaService verificacionColegiatura,
            LimiteLoginService limiteLogin,
            ConsentimientoService consentimientos
    ) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.tokenRefrescoService = tokenRefrescoService;
        this.perfilOdontologoRepository = perfilOdontologoRepository;
        this.reputacionPacienteRepository = reputacionPacienteRepository;
        this.verificacionColegiatura = verificacionColegiatura;
        this.limiteLogin = limiteLogin;
        this.consentimientos = consentimientos;
    }

    @Transactional
    public AuthResponse registrar(RegistroRequest request, HttpServletRequest httpRequest) {
        validarDatosNuevos(request.correo(), request.numeroDocumento(), request.telefono(), request.rol(),
                request.numeroColegiatura());

        Usuario usuario = Usuario.builder()
                .numeroDocumento(request.numeroDocumento())
                .tipoDocumento(request.tipoDocumento())
                .nombreCompleto(request.nombreCompleto())
                .telefono(request.telefono())
                .correo(request.correo())
                .passwordHash(passwordEncoder.encode(request.password()))
                .rol(request.rol())
                .metodoRegistro(MetodoAutenticacion.PASSWORD)
                .build();

        return crearCuenta(usuario, request.numeroColegiatura(), httpRequest);
    }

    /** Revisa que correo, documento, teléfono y COP no estén usados por otra cuenta (409) y que el odontólogo traiga COP (400). */
    public void validarDatosNuevos(String correo, String numeroDocumento, String telefono, RolUsuario rol,
                                   String numeroColegiatura) {
        if (usuarioRepository.existsByCorreo(correo)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El correo ya está registrado");
        }
        if (usuarioRepository.existsByNumeroDocumento(numeroDocumento)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El número de documento ya está registrado");
        }
        if (usuarioRepository.existsByTelefono(telefono)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El teléfono ya está registrado");
        }

        if (rol == RolUsuario.ODONTOLOGO) {
            if (numeroColegiatura == null || numeroColegiatura.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El número de colegiatura (COP) es obligatorio para registrarse como odontólogo");
            }
            if (perfilOdontologoRepository.existsByNumeroColegiatura(numeroColegiatura)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ese número de colegiatura ya está registrado por otra cuenta");
            }
        }
    }

    /**
     * Guarda la cuenta nueva (con contraseña o con Google), registra el consentimiento de privacidad, crea la
     * reputación del paciente o el perfil del odontólogo (verificado en el COP) y entrega los tokens.
     */
    @Transactional
    public AuthResponse crearCuenta(Usuario nuevo, String numeroColegiatura, HttpServletRequest httpRequest) {
        Usuario usuario = usuarioRepository.save(nuevo);
        // RNF07: queda registrado qué versión de la política aceptó, cuándo y desde qué IP
        consentimientos.registrar(usuario.getId(), httpRequest.getRemoteAddr());

        if (usuario.getRol() == RolUsuario.PACIENTE) {
            ReputacionPaciente reputacion = ReputacionPaciente.builder()
                    .usuario(usuario)
                    .build();
            reputacionPacienteRepository.save(reputacion);
        } else {
            crearPerfilOdontologoConVerificacion(usuario, numeroColegiatura);
        }

        return emitirTokens(usuario, httpRequest);
    }

    /** Access token (15 min) + refresh token nuevo para este dispositivo. */
    public AuthResponse emitirTokens(Usuario usuario, HttpServletRequest httpRequest) {
        String accessToken = jwtService.generateAccessToken(new UsuarioPrincipal(usuario));
        String refreshToken = tokenRefrescoService.crearParaUsuario(usuario, httpRequest);
        return new AuthResponse(accessToken, refreshToken, usuario.getRol().name(), usuario.getNombreCompleto(),
                usuario.getEmailVerificadoEn() != null);
    }

    /** Crea el perfil y lo verifica en el COP; si el COP no responde, queda PENDIENTE y se reintenta solo (RF09). */
    private void crearPerfilOdontologoConVerificacion(Usuario usuario, String numeroColegiatura) {
        verificacionColegiatura.verificarYGuardar(PerfilOdontologo.builder()
                .usuario(usuario)
                .numeroColegiatura(numeroColegiatura)
                .build());
    }

    /**
     * Inicio de sesión con límite de intentos: 5 contraseñas incorrectas bloquean la cuenta 15 minutos
     * y 20 fallos desde una misma IP bloquean esa IP (ver {@link LimiteLoginService}).
     */
    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        limiteLogin.verificar(request.correo(), ip);

        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.correo(), request.password())
            );
        } catch (BadCredentialsException e) {
            int quedan = limiteLogin.registrarFallo(request.correo(), ip);
            if (quedan == 0) {
                throw new LoginBloqueadoException(limiteLogin.segundosDeBloqueo());
            }
            if (quedan > 0 && quedan <= 2) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Correo o contraseña incorrectos. Te "
                        + (quedan == 1 ? "queda 1 intento" : "quedan " + quedan + " intentos")
                        + " antes de que la cuenta se bloquee por unos minutos");
            }
            throw e;
        }
        limiteLogin.limpiar(request.correo());

        UsuarioPrincipal principal = (UsuarioPrincipal) auth.getPrincipal();
        String accessToken = jwtService.generateAccessToken(principal);
        String refreshToken = tokenRefrescoService.crearParaUsuario(principal.getUsuario(), httpRequest);

        return new AuthResponse(accessToken, refreshToken, principal.getUsuario().getRol().name(),
                principal.getUsuario().getNombreCompleto(), principal.getUsuario().getEmailVerificadoEn() != null);
    }

    // @Transactional: el Usuario del token se carga de forma perezosa (LAZY). Sin una transacción
    // abierta aquí, leer usuario.getCorreo() lanza LazyInitializationException (open-in-view=false).
    @Transactional
    public AuthResponse refrescar(String refreshTokenEntrante, HttpServletRequest httpRequest) {
        TokenRefrescoService.ResultadoRotacion resultado = tokenRefrescoService.rotar(refreshTokenEntrante, httpRequest);

        Usuario usuario = resultado.usuario();
        UsuarioPrincipal principal = new UsuarioPrincipal(usuario);
        String nuevoAccessToken = jwtService.generateAccessToken(principal);

        return new AuthResponse(nuevoAccessToken, resultado.refreshTokenPlano(), usuario.getRol().name(),
                usuario.getNombreCompleto(), usuario.getEmailVerificadoEn() != null);
    }
}
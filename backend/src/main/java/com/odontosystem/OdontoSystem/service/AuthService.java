package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.dto.AuthResponse;
import com.odontosystem.OdontoSystem.dto.LoginRequest;
import com.odontosystem.OdontoSystem.dto.RegistroRequest;
import com.odontosystem.OdontoSystem.entity.*;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ReputacionPacienteRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import com.odontosystem.OdontoSystem.repository.VerificacionCopRepository;
import com.odontosystem.OdontoSystem.security.JwtService;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final TokenRefrescoService tokenRefrescoService;
    private final PerfilOdontologoRepository perfilOdontologoRepository;
    private final ReputacionPacienteRepository reputacionPacienteRepository;
    private final VerificacionCopRepository verificacionCopRepository;
    private final ColegioOdontologicoVerificacionService verificacionCopService;

    public AuthService(
            UsuarioRepository usuarioRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            TokenRefrescoService tokenRefrescoService,
            PerfilOdontologoRepository perfilOdontologoRepository,
            ReputacionPacienteRepository reputacionPacienteRepository,
            VerificacionCopRepository verificacionCopRepository,
            ColegioOdontologicoVerificacionService verificacionCopService
    ) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.tokenRefrescoService = tokenRefrescoService;
        this.perfilOdontologoRepository = perfilOdontologoRepository;
        this.reputacionPacienteRepository = reputacionPacienteRepository;
        this.verificacionCopRepository = verificacionCopRepository;
        this.verificacionCopService = verificacionCopService;
    }

    @Transactional
    public AuthResponse registrar(RegistroRequest request, HttpServletRequest httpRequest) {
        if (usuarioRepository.existsByCorreo(request.correo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El correo ya está registrado");
        }
        if (usuarioRepository.existsByNumeroDocumento(request.numeroDocumento())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El número de documento ya está registrado");
        }
        if (usuarioRepository.existsByTelefono(request.telefono())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El teléfono ya está registrado");
        }

        if (request.rol() == RolUsuario.ODONTOLOGO) {
            if (request.numeroColegiatura() == null || request.numeroColegiatura().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El número de colegiatura (COP) es obligatorio para registrarse como odontólogo");
            }
            if (perfilOdontologoRepository.existsByNumeroColegiatura(request.numeroColegiatura())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ese número de colegiatura ya está registrado por otra cuenta");
            }
        }

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

        usuario = usuarioRepository.save(usuario);

        if (request.rol() == RolUsuario.PACIENTE) {
            ReputacionPaciente reputacion = ReputacionPaciente.builder()
                    .usuario(usuario)
                    .build();
            reputacionPacienteRepository.save(reputacion);
        } else {
            crearPerfilOdontologoConVerificacion(usuario, request.numeroColegiatura());
        }

        UsuarioPrincipal principal = new UsuarioPrincipal(usuario);
        String accessToken = jwtService.generateAccessToken(principal);
        String refreshToken = tokenRefrescoService.crearParaUsuario(usuario, httpRequest);

        return new AuthResponse(accessToken, refreshToken, usuario.getRol().name(), usuario.getNombreCompleto());
    }

    private void crearPerfilOdontologoConVerificacion(Usuario usuario, String numeroColegiatura) {
        PerfilOdontologo perfil = PerfilOdontologo.builder()
                .usuario(usuario)
                .numeroColegiatura(numeroColegiatura)
                .build();

        var resultado = verificacionCopService.verificar(numeroColegiatura);

        ResultadoVerificacionCop resultadoParaLog;
        String detalle;

        switch (resultado.estado()) {
            case VERIFICADO_IDENTIDAD -> {
                boolean nombreCoincide = nombresCoinciden(usuario.getNombreCompleto(), resultado.nombreEncontrado());
                if (nombreCoincide) {
                    perfil.setColegiaturaVerificada(true);
                    perfil.setColegiaturaVerificadaEn(java.time.OffsetDateTime.now());
                    perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.VERIFICADO);
                    perfil.setVerificadoEn(java.time.OffsetDateTime.now());
                    resultadoParaLog = ResultadoVerificacionCop.VERIFICADO;
                    detalle = "Identidad confirmada contra el registro nacional COP: " + resultado.nombreEncontrado()
                            + " (" + resultado.region() + ").";
                } else {
                    perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.OBSERVADO);
                    perfil.setNotaObservacion("El nombre registrado no coincide claramente con el nombre encontrado en el COP ("
                            + resultado.nombreEncontrado() + "). Requiere revisión.");
                    resultadoParaLog = ResultadoVerificacionCop.RECHAZADO;
                    detalle = "COP encontrado pero el nombre no coincide: esperado='" + usuario.getNombreCompleto()
                            + "', encontrado='" + resultado.nombreEncontrado() + "'.";
                }
            }
            case NO_ENCONTRADO -> {
                perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.RECHAZADO);
                perfil.setNotaObservacion("No se encontró el número de colegiatura " + numeroColegiatura + " en el registro nacional del COP.");
                resultadoParaLog = ResultadoVerificacionCop.RECHAZADO;
                detalle = "Número de COP no encontrado en el buscador nacional.";
            }
            default -> {
                perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.PENDIENTE);
                perfil.setNotaObservacion("No se pudo verificar automáticamente en este momento; se reintentará.");
                resultadoParaLog = ResultadoVerificacionCop.ERROR;
                detalle = "Error de red/parsing al consultar el buscador nacional del COP.";
            }
        }

        perfilOdontologoRepository.save(perfil);

        VerificacionCop log = VerificacionCop.builder()
                .odontologo(perfil)
                .numeroCop(numeroColegiatura)
                .resultado(resultadoParaLog)
                .detalle(detalle)
                .build();
        verificacionCopRepository.save(log);
    }

    /** Compara nombres tolerando orden distinto, acentos y mayúsculas/minúsculas. */
    private boolean nombresCoinciden(String nombreA, String nombreB) {
        if (nombreB == null) return false;
        Set<String> tokensA = normalizarYTokenizar(nombreA);
        Set<String> tokensB = normalizarYTokenizar(nombreB);
        long coincidencias = tokensA.stream().filter(tokensB::contains).count();
        int minimo = Math.min(tokensA.size(), tokensB.size());
        return coincidencias >= Math.max(1, minimo - 1); // tolera 1 token de diferencia
    }

    private Set<String> normalizarYTokenizar(String texto) {
        String sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase();
        return Arrays.stream(sinAcentos.split("\\s+"))
                .filter(t -> !t.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
    }

    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.correo(), request.password())
        );

        UsuarioPrincipal principal = (UsuarioPrincipal) auth.getPrincipal();
        String accessToken = jwtService.generateAccessToken(principal);
        String refreshToken = tokenRefrescoService.crearParaUsuario(principal.getUsuario(), httpRequest);

        return new AuthResponse(accessToken, refreshToken, principal.getUsuario().getRol().name(), principal.getUsuario().getNombreCompleto());
    }

    // @Transactional: el Usuario del token se carga de forma perezosa (LAZY). Sin una transacción
    // abierta aquí, leer usuario.getCorreo() lanza LazyInitializationException (open-in-view=false).
    @Transactional
    public AuthResponse refrescar(String refreshTokenEntrante, HttpServletRequest httpRequest) {
        TokenRefrescoService.ResultadoRotacion resultado = tokenRefrescoService.rotar(refreshTokenEntrante, httpRequest);

        Usuario usuario = resultado.usuario();
        UsuarioPrincipal principal = new UsuarioPrincipal(usuario);
        String nuevoAccessToken = jwtService.generateAccessToken(principal);

        return new AuthResponse(nuevoAccessToken, resultado.refreshTokenPlano(), usuario.getRol().name(), usuario.getNombreCompleto());
    }
}
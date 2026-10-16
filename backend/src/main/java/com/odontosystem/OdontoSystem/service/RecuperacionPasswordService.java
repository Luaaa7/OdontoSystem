package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.notificaciones.EnviadorCorreo;
import com.odontosystem.OdontoSystem.notificaciones.PlantillasCorreo;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import com.odontosystem.OdontoSystem.security.JwtService;
import com.odontosystem.OdontoSystem.security.LimiteLoginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * "Olvidé mi contraseña" con un código de 6 dígitos enviado por correo.
 * <ul>
 *   <li>El código vale 15 minutos y se guarda en Redis solo como hash (SHA-256), nunca en claro.</li>
 *   <li>5 intentos fallidos anulan el código; hay que pedir otro.</li>
 *   <li>Solo se puede pedir un código por correo cada 60 segundos (evita llenar el buzón de alguien).</li>
 *   <li>La respuesta a "pedir código" es siempre la misma, exista o no la cuenta: así nadie puede averiguar
 *       qué correos están registrados.</li>
 *   <li>Al cambiar la contraseña se cierran todas las sesiones abiertas y se borra el bloqueo de login.</li>
 * </ul>
 */
@Service
public class RecuperacionPasswordService {

    private static final Logger log = LoggerFactory.getLogger(RecuperacionPasswordService.class);

    static final String PREFIJO = "odontosystem:recuperar:";
    static final Duration VIGENCIA = Duration.ofMinutes(15);
    static final Duration ESPERA_REENVIO = Duration.ofSeconds(60);
    static final int MAX_INTENTOS = 5;

    private static final String CODIGO_INVALIDO = "El código no es válido o ya venció. Pide uno nuevo.";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final EnviadorCorreo enviador;
    private final PlantillasCorreo plantillas;
    private final JwtService jwtService;
    private final RevocacionSesionesService revocacionSesiones;
    private final LimiteLoginService limiteLogin;

    public RecuperacionPasswordService(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder,
                                       StringRedisTemplate redis, EnviadorCorreo enviador, PlantillasCorreo plantillas,
                                       JwtService jwtService, RevocacionSesionesService revocacionSesiones,
                                       LimiteLoginService limiteLogin) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
        this.enviador = enviador;
        this.plantillas = plantillas;
        this.jwtService = jwtService;
        this.revocacionSesiones = revocacionSesiones;
        this.limiteLogin = limiteLogin;
    }

    /** Envía el código si la cuenta existe y está activa. No dice si existe o no. */
    public void solicitar(String correo) {
        if (!enviador.activo()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La recuperación de contraseña no está disponible en este momento");
        }
        String limpio = correo.strip();
        Optional<Usuario> usuario = usuarioRepository.findByCorreo(limpio).filter(u -> Boolean.TRUE.equals(u.getEstaActivo()));
        if (usuario.isEmpty()) {
            return; // misma respuesta que si existiera
        }

        String clave = clave(limpio);
        String codigo = String.format("%06d", RANDOM.nextInt(1_000_000));
        try {
            Boolean primero = redis.opsForValue().setIfAbsent(clave + ":espera", "1", ESPERA_REENVIO);
            if (!Boolean.TRUE.equals(primero)) {
                return; // ya se envió uno hace menos de 60 s
            }
            redis.opsForValue().set(clave + ":codigo", jwtService.hashearToken(codigo), VIGENCIA);
            redis.delete(clave + ":intentos");
        } catch (RuntimeException e) {
            log.warn("Redis no disponible al generar el código de recuperación: {}", e.getMessage());
            throw noDisponible();
        }

        Usuario u = usuario.get();
        try {
            String html = plantillas.armar("recuperar-password", Map.of(
                    "nombre", u.getNombreCompleto(),
                    "codigo", codigo,
                    "minutos", VIGENCIA.toMinutes()));
            enviador.enviar(u.getCorreo(), u.getNombreCompleto(), "Tu código para cambiar la contraseña", html);
        } catch (RuntimeException e) {
            log.warn("No se pudo enviar el código de recuperación: {}", e.getMessage());
            borrar(clave);
            throw noDisponible();
        }
    }

    /** Cambia la contraseña si el código es correcto, y cierra todas las sesiones de la cuenta. */
    @Transactional
    public void restablecer(String correo, String codigo, String passwordNueva) {
        String limpio = correo.strip();
        String clave = clave(limpio);

        try {
            String guardado = redis.opsForValue().get(clave + ":codigo");
            if (guardado == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CODIGO_INVALIDO);
            }
            Long intentos = redis.opsForValue().increment(clave + ":intentos");
            redis.expire(clave + ":intentos", VIGENCIA);
            if (intentos != null && intentos > MAX_INTENTOS) {
                borrar(clave);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Demasiados intentos con este código. Pide uno nuevo.");
            }
            if (!iguales(jwtService.hashearToken(codigo), guardado)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CODIGO_INVALIDO);
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Redis no disponible al revisar el código de recuperación: {}", e.getMessage());
            throw noDisponible();
        }

        Usuario usuario = usuarioRepository.findByCorreo(limpio)
                .filter(u -> Boolean.TRUE.equals(u.getEstaActivo()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, CODIGO_INVALIDO));
        usuario.setPasswordHash(passwordEncoder.encode(passwordNueva));
        usuarioRepository.save(usuario);

        borrar(clave);
        revocacionSesiones.revocarTodasLasSesionesDelUsuario(usuario.getId());
        limiteLogin.limpiar(limpio);
    }

    // ------------------------------------------------------------------

    static String clave(String correo) {
        return PREFIJO + correo.toLowerCase(Locale.ROOT);
    }

    private void borrar(String clave) {
        try {
            redis.delete(java.util.List.of(clave + ":codigo", clave + ":intentos"));
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar el código de recuperación: {}", e.getMessage());
        }
    }

    /** Compara en tiempo constante para no dar pistas por cuánto tarda. */
    private static boolean iguales(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseStatusException noDisponible() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "No se pudo procesar la recuperación de contraseña. Inténtalo en unos minutos.");
    }
}

package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.entity.CanalNotificacion;
import com.odontosystem.OdontoSystem.entity.OtpVerificacion;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.notificaciones.EnviadorCorreo;
import com.odontosystem.OdontoSystem.notificaciones.PlantillasCorreo;
import com.odontosystem.OdontoSystem.repository.OtpVerificacionRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * OTP por correo: verifica que el correo de la cuenta es de quien se registró (tabla otp_verificaciones).
 * <ul>
 *   <li>Al registrarse con contraseña se envía un código de 6 dígitos; se puede pedir otro (uno por minuto).</li>
 *   <li>Vale 15 minutos y solo el último código sin usar; se guarda como SHA-256, nunca en claro.</li>
 *   <li>5 intentos fallidos anulan el código; hay que pedir otro.</li>
 *   <li>Sin el correo verificado el paciente no puede reservar (app.correo.verificacion-obligatoria).
 *       Las cuentas de Google ya vienen verificadas. Si no hay proveedor de correo, no se exige.</li>
 * </ul>
 */
@Service
public class VerificacionCorreoService {

    private static final Logger log = LoggerFactory.getLogger(VerificacionCorreoService.class);

    static final Duration VIGENCIA = Duration.ofMinutes(15);
    static final Duration ESPERA_REENVIO = Duration.ofSeconds(60);
    static final int MAX_INTENTOS = 5;
    static final String CODIGO_INVALIDO = "El código no es válido o ya venció. Pide uno nuevo.";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UsuarioRepository usuarios;
    private final OtpVerificacionRepository otps;
    private final EnviadorCorreo enviador;
    private final PlantillasCorreo plantillas;
    private final Clock clock;
    private final boolean obligatoria;

    public VerificacionCorreoService(UsuarioRepository usuarios, OtpVerificacionRepository otps, EnviadorCorreo enviador,
                                     PlantillasCorreo plantillas, Clock clock,
                                     @Value("${app.correo.verificacion-obligatoria:true}") boolean obligatoria) {
        this.usuarios = usuarios;
        this.otps = otps;
        this.enviador = enviador;
        this.plantillas = plantillas;
        this.clock = clock;
        this.obligatoria = obligatoria;
    }

    /** Envía un código nuevo al correo de la cuenta. 409 si ya está verificado; 429 si pidió otro hace menos de 1 min. */
    @Transactional
    public void enviarCodigo(UUID usuarioId) {
        Usuario usuario = exigir(usuarioId);
        if (usuario.getEmailVerificadoEn() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu correo ya está verificado");
        }
        if (!enviador.activo()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El envío de correos no está disponible en este momento");
        }
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        boolean muyPronto = otps.findFirstByDestinoIgnoreCaseOrderByCreadoEnDesc(usuario.getCorreo())
                .map(o -> o.getCreadoEn().plus(ESPERA_REENVIO).isAfter(ahora))
                .orElse(false);
        if (muyPronto) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Ya te enviamos un código hace menos de un minuto; revisa tu correo (también la carpeta de spam)");
        }

        String codigo = String.format("%06d", RANDOM.nextInt(1_000_000));
        otps.save(OtpVerificacion.builder()
                .usuarioId(usuario.getId())
                .destino(usuario.getCorreo())
                .canal(CanalNotificacion.EMAIL)
                .codigoHash(sha256(codigo))
                .intentos((short) 0)
                .creadoEn(ahora)
                .expiraEn(ahora.plus(VIGENCIA))
                .build());
        try {
            String html = plantillas.armar("verificar-correo", Map.of(
                    "nombre", primerNombre(usuario.getNombreCompleto()),
                    "codigo", codigo,
                    "minutos", VIGENCIA.toMinutes()));
            enviador.enviar(usuario.getCorreo(), usuario.getNombreCompleto(), "Tu código para verificar tu correo", html);
        } catch (RuntimeException e) {
            // Se deshace el código guardado: no sirve uno que nunca llegó
            log.warn("No se pudo enviar el código de verificación de correo: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No pudimos enviar el código. Inténtalo en unos minutos.");
        }
    }

    /**
     * Revisa el código y marca el correo como verificado. Un intento fallido se guarda aunque responda 400
     * (por eso no se deshace la transacción con ResponseStatusException).
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public UsuarioService.UsuarioResponse verificar(UUID usuarioId, String codigo) {
        Usuario usuario = exigir(usuarioId);
        if (usuario.getEmailVerificadoEn() != null) {
            return UsuarioService.aRespuesta(usuario); // ya estaba: no es un error
        }
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        OtpVerificacion otp = otps.findFirstByUsuarioIdAndConsumidoEnIsNullOrderByCreadoEnDesc(usuario.getId())
                .filter(o -> o.getDestino().equalsIgnoreCase(usuario.getCorreo()))
                .filter(o -> o.getExpiraEn().isAfter(ahora))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, CODIGO_INVALIDO));
        if (otp.getIntentos() >= MAX_INTENTOS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Demasiados intentos con este código. Pide uno nuevo.");
        }
        String limpio = codigo == null ? "" : codigo.strip();
        if (!MessageDigest.isEqual(sha256(limpio).getBytes(StandardCharsets.UTF_8),
                otp.getCodigoHash().getBytes(StandardCharsets.UTF_8))) {
            otp.setIntentos((short) (otp.getIntentos() + 1));
            otps.save(otp);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CODIGO_INVALIDO);
        }
        otp.setConsumidoEn(ahora);
        otps.save(otp);
        usuario.setEmailVerificadoEn(ahora);
        usuarios.save(usuario);
        return UsuarioService.aRespuesta(usuario);
    }

    /** Justo después de registrarse con contraseña. Si el correo no sale, la cuenta igual queda creada. */
    public void enviarAlRegistrarse(String correo) {
        try {
            usuarios.findByCorreo(correo.strip()).ifPresent(u -> enviarCodigo(u.getId()));
        } catch (RuntimeException e) {
            log.info("No se envió el código de verificación al registrarse ({}); el usuario puede pedir otro",
                    e.getMessage());
        }
    }

    /** Antes de reservar: 403 si el correo no está verificado (cuando la verificación es obligatoria y hay correo). */
    @Transactional(readOnly = true)
    public void exigirVerificado(UUID usuarioId) {
        if (!obligatoria || !enviador.activo()) {
            return;
        }
        if (exigir(usuarioId).getEmailVerificadoEn() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Verifica tu correo antes de reservar: ingresa el código que te enviamos o pide uno nuevo");
        }
    }

    // ------------------------------------------------------------------

    private Usuario exigir(UUID usuarioId) {
        return usuarios.findById(usuarioId)
                .filter(u -> !Boolean.FALSE.equals(u.getEstaActivo()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
    }

    static String sha256(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private static String primerNombre(String nombreCompleto) {
        return nombreCompleto == null || nombreCompleto.isBlank() ? "" : nombreCompleto.trim().split("\\s+")[0];
    }
}

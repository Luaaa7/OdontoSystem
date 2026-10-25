package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.citas.CitaService;
import com.odontosystem.OdontoSystem.entity.CanalNotificacion;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.OtpVerificacion;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.notificaciones.EnviadorCorreo;
import com.odontosystem.OdontoSystem.notificaciones.PlantillasCorreo;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.OtpVerificacionRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import com.odontosystem.OdontoSystem.service.RevocacionSesionesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Eliminación de la cuenta (requisito de Google Play y derecho de cancelación de la Ley N.º 29733).
 * <ul>
 *   <li>Desde la app: el usuario confirma escribiendo ELIMINAR y, si tiene contraseña, con su contraseña.</li>
 *   <li>Desde la web, sin la app (/eliminar-cuenta): pide un código de 6 dígitos a su correo y lo ingresa.</li>
 * </ul>
 * Qué pasa con los datos:
 * <ul>
 *   <li>Se cancelan sus citas futuras (con la devolución que corresponda según las reglas de cancelación).</li>
 *   <li>Se BORRAN: sesiones, dispositivos (push), códigos OTP, conversaciones del chatbot y el historial de avisos.</li>
 *   <li>Se ANONIMIZAN los datos personales de la cuenta: nombre, correo, teléfono, documento, foto y acceso con Google.
 *       La cuenta queda desactivada y ya no se puede entrar.</li>
 *   <li>Se CONSERVAN, sin datos de contacto, las citas, pagos, historias clínicas, reseñas, reclamos y el registro del
 *       consentimiento: los exige la ley (historia clínica, contabilidad) o sirven para resolver reclamos.</li>
 *   <li>Odontólogo: además se desactiva su perfil y sus servicios, y se borran su RUC y CCI.</li>
 * </ul>
 */
@Service
public class EliminacionCuentaService {

    private static final Logger log = LoggerFactory.getLogger(EliminacionCuentaService.class);

    static final String CONFIRMACION = "ELIMINAR";
    static final String PREFIJO_DESTINO = "baja:";
    static final Duration VIGENCIA = Duration.ofMinutes(15);
    static final Duration ESPERA_REENVIO = Duration.ofSeconds(60);
    static final int MAX_INTENTOS = 5;
    static final String CODIGO_INVALIDO = "El código no es válido o ya venció. Pide uno nuevo.";
    private static final Set<EstadoCita> ACTIVAS =
            Set.of(EstadoCita.PENDIENTE_PAGO, EstadoCita.PENDIENTE_CONFIRMACION, EstadoCita.CONFIRMADA);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UsuarioRepository usuarios;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final CitaRepository citas;
    private final CitaService citaService;
    private final RevocacionSesionesService revocacionSesiones;
    private final OtpVerificacionRepository otps;
    private final EnviadorCorreo enviador;
    private final PlantillasCorreo plantillas;
    private final Clock clock;

    public EliminacionCuentaService(UsuarioRepository usuarios, PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
                                    CitaRepository citas, CitaService citaService,
                                    RevocacionSesionesService revocacionSesiones, OtpVerificacionRepository otps,
                                    EnviadorCorreo enviador, PlantillasCorreo plantillas, Clock clock) {
        this.usuarios = usuarios;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.citas = citas;
        this.citaService = citaService;
        this.revocacionSesiones = revocacionSesiones;
        this.otps = otps;
        this.enviador = enviador;
        this.plantillas = plantillas;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ desde la app

    /** El usuario autenticado elimina su cuenta. password es obligatoria si la cuenta tiene contraseña. */
    @Transactional
    public void eliminarMiCuenta(UUID usuarioId, String password, String confirmacion) {
        if (confirmacion == null || !CONFIRMACION.equals(confirmacion.strip().toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Para confirmar escribe ELIMINAR");
        }
        Usuario usuario = activo(usuarioId);
        if (usuario.getPasswordHash() != null
                && (password == null || !passwordEncoder.matches(password, usuario.getPasswordHash()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña no es correcta");
        }
        anonimizar(usuario);
    }

    // ------------------------------------------------------------------ desde la web (sin la app)

    /** Envía un código para eliminar la cuenta. Siempre responde igual, exista o no la cuenta. */
    @Transactional
    public void solicitarCodigo(String correo) {
        if (!enviador.activo()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El envío de correos no está disponible en este momento");
        }
        String limpio = correo == null ? "" : correo.strip();
        var usuario = usuarios.findByCorreo(limpio).filter(u -> Boolean.TRUE.equals(u.getEstaActivo()));
        if (usuario.isEmpty()) {
            return; // misma respuesta que si existiera
        }
        String destino = destino(limpio);
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        boolean muyPronto = otps.findFirstByDestinoIgnoreCaseOrderByCreadoEnDesc(destino)
                .map(o -> o.getCreadoEn().plus(ESPERA_REENVIO).isAfter(ahora))
                .orElse(false);
        if (muyPronto) {
            return;
        }
        Usuario u = usuario.get();
        String codigo = String.format("%06d", RANDOM.nextInt(1_000_000));
        otps.save(OtpVerificacion.builder()
                .usuarioId(u.getId())
                .destino(destino)
                .canal(CanalNotificacion.EMAIL)
                .codigoHash(VerificacionCorreoService.sha256(codigo))
                .intentos((short) 0)
                .creadoEn(ahora)
                .expiraEn(ahora.plus(VIGENCIA))
                .build());
        try {
            String html = plantillas.armar("eliminar-cuenta", Map.of(
                    "nombre", primerNombre(u.getNombreCompleto()),
                    "codigo", codigo,
                    "minutos", VIGENCIA.toMinutes()));
            enviador.enviar(u.getCorreo(), u.getNombreCompleto(), "Código para eliminar tu cuenta de OdontoSystem", html);
        } catch (RuntimeException e) {
            log.warn("No se pudo enviar el código para eliminar la cuenta: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No pudimos enviar el código. Inténtalo en unos minutos.");
        }
    }

    /** Elimina la cuenta si el código es correcto. Un intento fallido se guarda aunque responda 400. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void eliminarConCodigo(String correo, String codigo) {
        String limpio = correo == null ? "" : correo.strip();
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        Usuario usuario = usuarios.findByCorreo(limpio).filter(u -> Boolean.TRUE.equals(u.getEstaActivo()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, CODIGO_INVALIDO));
        OtpVerificacion otp = otps.findFirstByDestinoIgnoreCaseOrderByCreadoEnDesc(destino(limpio))
                .filter(o -> o.getConsumidoEn() == null)
                .filter(o -> usuario.getId().equals(o.getUsuarioId()))
                .filter(o -> o.getExpiraEn().isAfter(ahora))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, CODIGO_INVALIDO));
        if (otp.getIntentos() >= MAX_INTENTOS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Demasiados intentos con este código. Pide uno nuevo.");
        }
        String limpioCodigo = codigo == null ? "" : codigo.strip();
        if (!MessageDigest.isEqual(VerificacionCorreoService.sha256(limpioCodigo).getBytes(StandardCharsets.UTF_8),
                otp.getCodigoHash().getBytes(StandardCharsets.UTF_8))) {
            otp.setIntentos((short) (otp.getIntentos() + 1));
            otps.save(otp);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CODIGO_INVALIDO);
        }
        otp.setConsumidoEn(ahora);
        otps.save(otp);
        anonimizar(usuario);
    }

    // ------------------------------------------------------------------

    /** Cancela lo pendiente, borra lo que no hace falta guardar y anonimiza la cuenta. */
    @Transactional
    public void anonimizar(Usuario usuario) {
        UUID id = usuario.getId();
        OffsetDateTime ahora = OffsetDateTime.now(clock);

        // 1) Citas futuras: se cancelan con las reglas de siempre (reembolso según quién cancela y la anticipación)
        List<Cita> pendientes = usuario.getRol() == RolUsuario.ODONTOLOGO
                ? citas.agenda(id, ahora, ahora.plusYears(2))
                : citas.delPaciente(id);
        String motivo = usuario.getRol() == RolUsuario.ODONTOLOGO
                ? "El odontólogo dejó la plataforma" : "El paciente eliminó su cuenta";
        for (Cita c : pendientes) {
            if (ACTIVAS.contains(c.getEstado()) && c.getInicioProgramado().isAfter(ahora)) {
                citaService.cancelar(id, c.getId(), motivo);
            }
        }

        // 2) Lo que no hace falta conservar se borra
        revocacionSesiones.revocarTodasLasSesionesDelUsuario(id);
        jdbc.update("DELETE FROM dispositivos_usuario WHERE usuario_id = ?", id);
        jdbc.update("DELETE FROM sesiones_chat WHERE usuario_id = ?", id); // sus mensajes se borran en cascada
        jdbc.update("DELETE FROM registro_notificaciones WHERE usuario_id = ?", id);
        jdbc.update("DELETE FROM sesiones_usuario WHERE usuario_id = ?", id);
        if (usuario.getRol() == RolUsuario.ODONTOLOGO) {
            jdbc.update("UPDATE perfiles_odontologo SET desactivado_en = ?, ruc = NULL, cci = NULL WHERE usuario_id = ?",
                    ahora, id);
            jdbc.update("UPDATE servicios SET esta_activo = FALSE WHERE odontologo_id = ?", id);
        }

        // 3) Datos personales anonimizados (correo, teléfono y documento siguen siendo únicos)
        String marca = id.toString().replace("-", "");
        usuario.setNombreCompleto("Usuario eliminado");
        usuario.setCorreo("eliminado-" + marca + "@odontosystem.invalid");
        usuario.setTelefono("X" + marca.substring(0, 19));
        usuario.setNumeroDocumento("X" + marca.substring(0, 19));
        usuario.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString())); // nadie la conoce
        usuario.setGoogleOauthSub(null);
        usuario.setFotoPerfilUrl(null);
        usuario.setBiometriaHabilitada(false);
        usuario.setEmailVerificadoEn(null);
        usuario.setTelefonoVerificadoEn(null);
        usuario.setEstaActivo(false);
        usuarios.save(usuario);
        // Los códigos OTP se borran al final (incluido el que se acaba de usar para confirmar)
        jdbc.update("DELETE FROM otp_verificaciones WHERE usuario_id = ?", id);
        log.info("Cuenta {} eliminada (anonimizada) a pedido del usuario", id);
    }

    private Usuario activo(UUID usuarioId) {
        return usuarios.findById(usuarioId)
                .filter(u -> Boolean.TRUE.equals(u.getEstaActivo()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
    }

    private static String destino(String correo) {
        return PREFIJO_DESTINO + correo.toLowerCase(Locale.ROOT);
    }

    private static String primerNombre(String nombreCompleto) {
        return nombreCompleto == null || nombreCompleto.isBlank() ? "" : nombreCompleto.trim().split("\\s+")[0];
    }
}

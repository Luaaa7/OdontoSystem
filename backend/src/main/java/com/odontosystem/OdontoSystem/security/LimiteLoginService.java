package com.odontosystem.OdontoSystem.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Límite de intentos de inicio de sesión (protección contra fuerza bruta), con contadores en Redis
 * para que valga igual con varias instancias del backend.
 * <ul>
 *   <li>Por cuenta: 5 contraseñas incorrectas seguidas bloquean el login de ese correo 15 minutos.</li>
 *   <li>Por IP: 20 fallos (con cualquier correo) bloquean esa IP 15 minutos; frena a quien prueba muchas cuentas.</li>
 *   <li>Un login correcto borra el contador de la cuenta (no el de la IP).</li>
 *   <li>Si Redis no responde, el login sigue funcionando sin límite: no se deja a nadie afuera por una caída.</li>
 * </ul>
 * Mientras el bloqueo dura no se revisa la contraseña, así que ni siquiera la correcta entra.
 */
@Service
public class LimiteLoginService {

    private static final Logger log = LoggerFactory.getLogger(LimiteLoginService.class);
    static final String PREFIJO = "odontosystem:login:fallos:";

    private final StringRedisTemplate redis;
    private final int maxPorCuenta;
    private final int maxPorIp;
    private final Duration bloqueo;

    public LimiteLoginService(StringRedisTemplate redis,
                              @Value("${app.login.max-intentos:5}") int maxPorCuenta,
                              @Value("${app.login.max-intentos-ip:20}") int maxPorIp,
                              @Value("${app.login.bloqueo-minutos:15}") long bloqueoMinutos) {
        this.redis = redis;
        this.maxPorCuenta = maxPorCuenta;
        this.maxPorIp = maxPorIp;
        this.bloqueo = Duration.ofMinutes(bloqueoMinutos);
    }

    /** Lanza {@link LoginBloqueadoException} si la cuenta o la IP están bloqueadas. */
    public void verificar(String correo, String ip) {
        try {
            long espera = Math.max(restante(claveCuenta(correo), maxPorCuenta), restante(claveIp(ip), maxPorIp));
            if (espera > 0) {
                throw new LoginBloqueadoException(espera);
            }
        } catch (LoginBloqueadoException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Redis no disponible al revisar intentos de login; se permite el intento: {}", e.getMessage());
        }
    }

    /** Cuenta un fallo para la cuenta y la IP. Devuelve cuántos intentos le quedan a la cuenta (o -1 si no se sabe). */
    public int registrarFallo(String correo, String ip) {
        try {
            sumar(claveIp(ip), maxPorIp);
            long fallos = sumar(claveCuenta(correo), maxPorCuenta);
            if (fallos >= maxPorCuenta) {
                log.warn("Login bloqueado {} min para {} tras {} intentos fallidos (IP {})",
                        bloqueo.toMinutes(), ocultar(correo), fallos, ip);
            }
            return (int) Math.max(0, maxPorCuenta - fallos);
        } catch (RuntimeException e) {
            log.warn("Redis no disponible al registrar un login fallido: {}", e.getMessage());
            return -1;
        }
    }

    public long segundosDeBloqueo() {
        return bloqueo.toSeconds();
    }

    public void limpiar(String correo) {
        try {
            redis.delete(claveCuenta(correo));
        } catch (RuntimeException e) {
            log.warn("No se pudo limpiar el contador de login: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    /** Suma un fallo; el primero abre la ventana y el que llega al máximo reinicia el plazo con el bloqueo completo. */
    private long sumar(String clave, int max) {
        Long valor = redis.opsForValue().increment(clave);
        long fallos = valor == null ? 0 : valor;
        if (fallos == 1 || fallos == max) {
            redis.expire(clave, bloqueo);
        }
        return fallos;
    }

    /** Segundos de bloqueo que quedan (0 si no está bloqueado). */
    private long restante(String clave, int max) {
        String valor = redis.opsForValue().get(clave);
        if (valor == null || Long.parseLong(valor) < max) {
            return 0;
        }
        Long ttl = redis.getExpire(clave, TimeUnit.SECONDS);
        return ttl == null || ttl < 0 ? bloqueo.toSeconds() : ttl;
    }

    static String claveCuenta(String correo) {
        return PREFIJO + "cuenta:" + (correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT));
    }

    static String claveIp(String ip) {
        return PREFIJO + "ip:" + (ip == null ? "desconocida" : ip);
    }

    /** En los logs no va el correo completo: l***@correo.pe */
    static String ocultar(String correo) {
        if (correo == null || !correo.contains("@")) return "***";
        return correo.charAt(0) + "***" + correo.substring(correo.indexOf('@'));
    }
}

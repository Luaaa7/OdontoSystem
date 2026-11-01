package com.odontosystem.OdontoSystem.usuarios;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * RNF07 / Ley N.º 29733 (protección de datos personales): guarda el consentimiento explícito del usuario a la
 * política de privacidad (versión aceptada, fecha e IP) en consentimientos_privacidad. Si la política cambia de
 * versión (PRIVACIDAD_VERSION), cada aceptación nueva queda como otra fila: así se sabe qué aceptó y cuándo.
 * Se usa SQL nativo porque la columna ip_origen es de tipo INET.
 */
@Service
public class ConsentimientoService {

    /** Solo dígitos, letras hexadecimales, puntos y dos puntos: una IPv4 o IPv6 válida para INET. */
    private static final Pattern IP = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    public record ConsentimientoResponse(String versionPolitica, OffsetDateTime aceptadoEn) {}

    public record PoliticaResponse(String version) {}

    /**
     * Si el usuario ya aceptó la versión vigente. Cuando la política cambia de versión, aceptada pasa a false
     * y el frontend debe pedirle que acepte la nueva (POST /api/usuarios/yo/consentimientos).
     */
    public record EstadoConsentimiento(String versionVigente, boolean aceptada, String ultimaVersionAceptada,
                                       OffsetDateTime ultimaAceptacionEn) {}

    private final EntityManager entityManager;
    private final String versionVigente;

    public ConsentimientoService(EntityManager entityManager,
                                 @Value("${app.privacidad.version:2026-10-v1}") String versionVigente) {
        this.entityManager = entityManager;
        this.versionVigente = versionVigente;
    }

    public String versionVigente() {
        return versionVigente;
    }

    /** Registra que el usuario aceptó la versión vigente. Si ya la había aceptado, no hace nada. */
    @Transactional
    public void registrar(UUID usuarioId, String ip) {
        entityManager.createNativeQuery("insert into consentimientos_privacidad (usuario_id, version_politica, ip_origen) "
                        + "values (:usuario, :version, cast(:ip as inet)) "
                        + "on conflict (usuario_id, version_politica) do nothing")
                .setParameter("usuario", usuarioId)
                .setParameter("version", versionVigente)
                .setParameter("ip", ipValida(ip))
                .executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<ConsentimientoResponse> deUsuario(UUID usuarioId) {
        @SuppressWarnings("unchecked")
        List<Object[]> filas = entityManager.createNativeQuery("select version_politica, aceptado_en "
                        + "from consentimientos_privacidad where usuario_id = :usuario order by aceptado_en desc")
                .setParameter("usuario", usuarioId)
                .getResultList();
        return filas.stream().map(f -> new ConsentimientoResponse((String) f[0], aFecha(f[1]))).toList();
    }

    /** ¿Aceptó la versión vigente? Y cuál fue la última que aceptó. */
    @Transactional(readOnly = true)
    public EstadoConsentimiento estado(UUID usuarioId) {
        return estadoDe(versionVigente, deUsuario(usuarioId));
    }

    /** Registra la aceptación de la versión vigente (si ya estaba, no duplica) y devuelve el estado. */
    @Transactional
    public EstadoConsentimiento aceptar(UUID usuarioId, String ip) {
        registrar(usuarioId, ip);
        return estado(usuarioId);
    }

    /** @param aceptados del más reciente al más antiguo, como los devuelve {@link #deUsuario} */
    static EstadoConsentimiento estadoDe(String vigente, List<ConsentimientoResponse> aceptados) {
        boolean aceptada = aceptados.stream().anyMatch(c -> vigente.equals(c.versionPolitica()));
        ConsentimientoResponse ultimo = aceptados.isEmpty() ? null : aceptados.get(0);
        return new EstadoConsentimiento(vigente, aceptada,
                ultimo == null ? null : ultimo.versionPolitica(), ultimo == null ? null : ultimo.aceptadoEn());
    }

    static String ipValida(String ip) {
        if (ip == null) return null;
        String limpia = ip.strip();
        return IP.matcher(limpia).matches() ? limpia : null;
    }

    private static OffsetDateTime aFecha(Object valor) {
        if (valor instanceof OffsetDateTime o) return o;
        if (valor instanceof java.time.Instant i) return i.atOffset(java.time.ZoneOffset.UTC);
        if (valor instanceof java.sql.Timestamp t) return t.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return null;
    }
}

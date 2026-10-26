package com.odontosystem.OdontoSystem.integracion;

import com.odontosystem.OdontoSystem.entity.CanalNotificacion;
import com.odontosystem.OdontoSystem.entity.OtpVerificacion;
import com.odontosystem.OdontoSystem.repository.OtpVerificacionRepository;
import com.odontosystem.OdontoSystem.usuarios.EliminacionCuentaService;
import com.odontosystem.OdontoSystem.usuarios.VerificacionCorreoService;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración contra un PostgreSQL 17 + PostGIS REAL (Docker, Testcontainers): se aplican las migraciones
 * V1..V10 desde cero y se comprueban las reglas que viven en la base de datos (triggers y restricciones), que las
 * pruebas unitarias con mocks no pueden cubrir.
 * <p>
 * Si la máquina no tiene Docker (p. ej. la laptop sin Docker Desktop), estas pruebas se SALTAN solas; en GitHub
 * Actions siempre corren. Redis y el correo no hacen falta: la app arranca igual sin ellos.
 */
@SpringBootTest(properties = {
        "DB_USERNAME=odonto", "DB_PASSWORD=odonto",
        "JWT_SECRET=clave-de-pruebas-de-integracion-con-mas-de-32-bytes",
        "MAIL_HOST=localhost", "MAIL_USERNAME=prueba", "MAIL_PASSWORD=prueba",
        "REDIS_HOST=localhost", "REDIS_PASSWORD=", "REDIS_SSL=false",
        "GOOGLE_CLIENT_ID=",
        // La imagen de PostGIS ya trae tablas en "public" (spatial_ref_sys): sin esto Flyway saltaría V1 y V2
        "spring.flyway.baseline-version=0"
})
@EnabledIf("dockerDisponible")
class BaseDeDatosIntegracionTest {

    private static final String USUARIO_BD = "odonto";
    private static GenericContainer<?> postgres;

    static boolean dockerDisponible() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    @SuppressWarnings("resource")
    private static synchronized GenericContainer<?> postgres() {
        if (postgres == null) {
            postgres = new GenericContainer<>("postgis/postgis:17-3.5")
                    .withEnv("POSTGRES_USER", USUARIO_BD)
                    .withEnv("POSTGRES_PASSWORD", USUARIO_BD)
                    .withEnv("POSTGRES_DB", USUARIO_BD)
                    .withExposedPorts(5432)
                    // La imagen arranca, corre sus scripts de PostGIS y reinicia: hay que esperar el segundo aviso
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                            .withStartupTimeout(Duration.ofMinutes(3)));
            postgres.start();
            crearRolesDeSupabase();
        }
        return postgres;
    }

    private static String urlJdbc() {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + USUARIO_BD;
    }

    /** V3 quita permisos a los roles anon y authenticated, que existen en Supabase pero no en un PostgreSQL limpio. */
    private static void crearRolesDeSupabase() {
        try (Connection c = DriverManager.getConnection(urlJdbc(), USUARIO_BD, USUARIO_BD);
             Statement s = c.createStatement()) {
            s.execute("CREATE ROLE anon NOLOGIN");
            s.execute("CREATE ROLE authenticated NOLOGIN");
            s.execute("CREATE SCHEMA IF NOT EXISTS extensions");
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo preparar la base de pruebas", e);
        }
    }

    @DynamicPropertySource
    static void baseDeDatos(DynamicPropertyRegistry registro) {
        postgres();
        registro.add("spring.datasource.url", BaseDeDatosIntegracionTest::urlJdbc);
        registro.add("spring.datasource.username", () -> USUARIO_BD);
        registro.add("spring.datasource.password", () -> USUARIO_BD);
    }

    // El contenedor se detiene solo al terminar la JVM (Testcontainers lo limpia con Ryuk).

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OtpVerificacionRepository otps;
    @Autowired private VerificacionCorreoService verificacionCorreo;
    @Autowired private EliminacionCuentaService eliminacionCuenta;
    @Autowired private UsuarioRepository usuarios;

    // ------------------------------------------------------------------ datos de prueba

    private static final String HASH_BCRYPT = "$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0";
    /** Mañana + 10 días a las 10:00 (hora de Lima), sin segundos. */
    private static final OffsetDateTime DIA = OffsetDateTime.now(ZoneOffset.ofHours(-5)).plusDays(10)
            .withHour(10).withMinute(0).truncatedTo(ChronoUnit.MINUTES);

    private UUID usuario(String rol) {
        UUID id = UUID.randomUUID();
        String n = String.valueOf(ThreadLocalRandom.current().nextLong(10_000_000L, 99_999_999L));
        jdbc.update("INSERT INTO usuarios (id, numero_documento, nombre_completo, telefono, correo, rol, password_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?::rol_usuario, ?)",
                id, n, "Prueba " + rol, "9" + n, "it-" + id + "@odontosystem.test", rol, HASH_BCRYPT);
        return id;
    }

    private record Consultorio(UUID paciente, UUID odontologo, UUID servicio) {}

    private Consultorio consultorio() {
        UUID paciente = usuario("PACIENTE");
        UUID odontologo = usuario("ODONTOLOGO");
        jdbc.update("INSERT INTO perfiles_odontologo (usuario_id, numero_colegiatura) VALUES (?, ?)",
                odontologo, "IT-" + odontologo.toString().substring(0, 8));
        UUID servicio = UUID.randomUUID();
        Short categoria = jdbc.queryForObject("SELECT min(id) FROM categorias_servicio", Short.class);
        jdbc.update("INSERT INTO servicios (id, odontologo_id, categoria_id, titulo, precio_total, duracion_minutos) "
                + "VALUES (?, ?, ?, 'Limpieza', 150.00, 30)", servicio, odontologo, categoria);
        return new Consultorio(paciente, odontologo, servicio);
    }

    private UUID cita(Consultorio c, UUID paciente, OffsetDateTime inicio, String estado, UUID origen) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO citas (id, paciente_id, odontologo_id, servicio_id, cita_origen_id, inicio_programado, "
                        + "fin_programado, precio_servicio, monto_deposito_requerido, duracion_minutos, estado) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 30, ?::estado_cita)",
                id, paciente, c.odontologo(), c.servicio(), origen, inicio, inicio.plusMinutes(30), estado);
        return id;
    }

    private UUID cita(Consultorio c, OffsetDateTime inicio, String estado) {
        return cita(c, c.paciente(), inicio, estado, null);
    }

    // ------------------------------------------------------------------ pruebas

    @Test
    @DisplayName("Las 10 migraciones se aplican desde cero y Hibernate valida todas las entidades contra el esquema")
    void migraciones() {
        Integer ultima = jdbc.queryForObject(
                "SELECT max(version::int) FROM flyway_schema_history WHERE type = 'SQL' AND success", Integer.class);
        Integer fallidas = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);
        assertThat(ultima).isEqualTo(10);
        assertThat(fallidas).isZero();
        // Si alguna entidad no coincidiera con la tabla (ddl-auto=validate), el contexto ni siquiera arrancaría.
    }

    @Test
    @DisplayName("Reputación: un PACIENTE recién registrado puede tener su fila; un ODONTOLOGO no (V10)")
    void reputacionSoloPacientes() {
        UUID paciente = usuario("PACIENTE");
        jdbc.update("INSERT INTO reputacion_paciente (usuario_id) VALUES (?)", paciente);
        assertThat(jdbc.queryForObject("SELECT estado::text FROM reputacion_paciente WHERE usuario_id = ?",
            String.class, paciente)).isEqualTo("EXCELENTE");

        UUID odontologo = usuario("ODONTOLOGO");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO reputacion_paciente (usuario_id) VALUES (?)", odontologo))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("rol PACIENTE");
}   
    
    @Test
    @DisplayName("Doble reserva: la BD rechaza dos citas activas del mismo odontólogo que se cruzan (ex_citas_sin_solape)")
    void sinDobleReserva() {
        Consultorio c = consultorio();
        cita(c, DIA, "CONFIRMADA");
        UUID otroPaciente = usuario("PACIENTE");

        assertThatThrownBy(() -> cita(c, otroPaciente, DIA.plusMinutes(15), "CONFIRMADA", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ex_citas_sin_solape");
        // Justo a continuación (10:30) sí se puede: el rango es [inicio, fin)
        cita(c, otroPaciente, DIA.plusMinutes(30), "CONFIRMADA", null);
    }

    @Test
    @DisplayName("Reprogramación: la cita REPROGRAMADA libera su horario y cada cita tiene como máximo una hija")
    void reprogramacionEnLaBd() {
        Consultorio c = consultorio();
        UUID original = cita(c, DIA, "CONFIRMADA");
        jdbc.update("UPDATE citas SET estado = 'REPROGRAMADA' WHERE id = ?", original);

        // El mismo horario ya está libre para la cita nueva
        cita(c, c.paciente(), DIA, "CONFIRMADA", original);

        // Una segunda "hija" de la misma cita viola uq_citas_cita_origen
        assertThatThrownBy(() -> cita(c, c.paciente(), DIA.plusHours(2), "CONFIRMADA", original))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_citas_cita_origen");
    }

    @Test
    @DisplayName("Solo un PACIENTE puede tener citas como paciente (trigger de roles)")
    void rolDelPaciente() {
        Consultorio c = consultorio();

        assertThatThrownBy(() -> cita(c, c.odontologo(), DIA, "PENDIENTE_PAGO", null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("rol PACIENTE");
    }

    @Test
    @DisplayName("El depósito del servicio lo calcula la BD (20 %) y la cita guarda una copia del precio")
    void depositoCalculado() {
        Consultorio c = consultorio();
        BigDecimal deposito = jdbc.queryForObject("SELECT monto_deposito FROM servicios WHERE id = ?",
                BigDecimal.class, c.servicio());
        assertThat(deposito).isEqualByComparingTo("30.00");

        UUID cita = cita(c, DIA, "PENDIENTE_PAGO");
        BigDecimal precio = jdbc.queryForObject("SELECT precio_servicio FROM citas WHERE id = ?", BigDecimal.class, cita);
        assertThat(precio).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("Un pago no puede pasar a VERIFICADO sin un aviso de la pasarela verificado (trigger de pagos)")
    void pagoSinAvisoNoSeVerifica() {
        Consultorio c = consultorio();
        UUID cita = cita(c, DIA, "PENDIENTE_PAGO");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO pagos (cita_id, metodo_pago, monto, estado) "
                + "VALUES (?, 'YAPE'::metodo_pago, 30.00, 'VERIFICADO'::estado_pago)", cita))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("webhook");
    }

    @Test
    @DisplayName("Bitácora: la BD registra la creación y cada cambio de estado de la cita")
    void bitacora() {
        Consultorio c = consultorio();
        UUID cita = cita(c, DIA, "PENDIENTE_PAGO");
        jdbc.update("UPDATE citas SET estado = 'CANCELADA_SIN_REEMBOLSO' WHERE id = ?", cita);

        List<String> estados = jdbc.queryForList(
                "SELECT estado_nuevo::text FROM bitacora_auditoria_citas WHERE cita_id = ? ORDER BY id", String.class, cita);
        assertThat(estados).containsExactly("PENDIENTE_PAGO", "CANCELADA_SIN_REEMBOLSO");
    }

    @Test
    @DisplayName("Eliminar cuenta: la BD acepta la anonimización y conserva las citas y el consentimiento")
    void eliminarCuentaEnLaBd() {
        Consultorio c = consultorio();
        UUID citaPasada = cita(c, DIA.minusDays(30), "COMPLETADA");
        jdbc.update("INSERT INTO consentimientos_privacidad (usuario_id, version_politica) VALUES (?, 'it-v1')", c.paciente());
        jdbc.update("INSERT INTO dispositivos_usuario (usuario_id, token_fcm) VALUES (?, 'token-de-prueba')", c.paciente());

        eliminacionCuenta.anonimizar(usuarios.findById(c.paciente()).orElseThrow());

        var fila = jdbc.queryForMap("SELECT nombre_completo, correo, esta_activo FROM usuarios WHERE id = ?", c.paciente());
        assertThat(fila.get("nombre_completo")).isEqualTo("Usuario eliminado");
        assertThat((String) fila.get("correo")).endsWith("@odontosystem.invalid");
        assertThat(fila.get("esta_activo")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispositivos_usuario WHERE usuario_id = ?",
                Integer.class, c.paciente())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM citas WHERE id = ?", Integer.class, citaPasada)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consentimientos_privacidad WHERE usuario_id = ?",
                Integer.class, c.paciente())).isEqualTo(1);
    }

    @Test
    @DisplayName("OTP por correo de punta a punta: el código guardado en otp_verificaciones verifica la cuenta")
    void otpDePuntaAPunta() throws Exception {
        UUID paciente = usuario("PACIENTE");
        OffsetDateTime ahora = OffsetDateTime.now();
        otps.save(OtpVerificacion.builder()
                .usuarioId(paciente)
                .destino("it-" + paciente + "@odontosystem.test")
                .canal(CanalNotificacion.EMAIL)
                .codigoHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest("482913".getBytes(StandardCharsets.UTF_8))))
                .intentos((short) 0)
                .creadoEn(ahora)
                .expiraEn(ahora.plusMinutes(15))
                .build());

        var r = verificacionCorreo.verificar(paciente, "482913");

        assertThat(r.correoVerificado()).isTrue();
        assertThat(jdbc.queryForObject("SELECT email_verificado_en IS NOT NULL FROM usuarios WHERE id = ?",
                Boolean.class, paciente)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_verificaciones WHERE usuario_id = ? AND consumido_en IS NOT NULL",
                Integer.class, paciente)).isEqualTo(1);
    }
}

package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.notificaciones.EnviadorCorreo;
import com.odontosystem.OdontoSystem.notificaciones.PlantillasCorreo;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import com.odontosystem.OdontoSystem.security.JwtService;
import com.odontosystem.OdontoSystem.security.LimiteLoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Olvidé mi contraseña": código de 6 dígitos por correo, guardado como hash en Redis. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecuperacionPasswordServiceTest {

    private static final String CORREO = "lucia@correo.pe";
    private static final String CLAVE = RecuperacionPasswordService.PREFIJO + CORREO;

    @Mock private UsuarioRepository usuarios;
    @Mock private PasswordEncoder encoder;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valores;
    @Mock private EnviadorCorreo enviador;
    @Mock private PlantillasCorreo plantillas;
    @Mock private RevocacionSesionesService revocacion;
    @Mock private LimiteLoginService limiteLogin;

    private final JwtService jwt = new JwtService();
    private RecuperacionPasswordService servicio;
    private Usuario lucia;

    @BeforeEach
    void setUp() {
        servicio = new RecuperacionPasswordService(usuarios, encoder, redis, enviador, plantillas, jwt, revocacion, limiteLogin);
        lucia = Usuario.builder().id(UUID.randomUUID()).correo(CORREO).nombreCompleto("Lucía Ramos")
                .rol(RolUsuario.PACIENTE).passwordHash("hash-viejo").estaActivo(true).build();

        when(redis.opsForValue()).thenReturn(valores);
        when(enviador.activo()).thenReturn(true);
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(lucia));
        when(valores.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(plantillas.armar(anyString(), anyMap())).thenReturn("<html>");
        when(encoder.encode(anyString())).thenAnswer(i -> "hash(" + i.getArgument(0) + ")");
    }

    // ---------------------------------------------------------------- pedir código

    @Test
    @DisplayName("Si la cuenta existe, envía un código de 6 dígitos y en Redis solo guarda su hash por 15 minutos")
    @SuppressWarnings("unchecked")
    void enviaCodigo() {
        servicio.solicitar(" " + CORREO + " ");

        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(plantillas).armar(eq("recuperar-password"), variables.capture());
        String codigo = (String) variables.getValue().get("codigo");
        assertThat(codigo).matches("\\d{6}");

        verify(valores).set(CLAVE + ":codigo", jwt.hashearToken(codigo), Duration.ofMinutes(15));
        verify(enviador).enviar(eq(CORREO), eq("Lucía Ramos"), anyString(), eq("<html>"));
    }

    @Test
    @DisplayName("Si el correo no está registrado responde igual, pero no envía nada (no revela qué cuentas existen)")
    void correoDesconocido() {
        when(usuarios.findByCorreo("nadie@correo.pe")).thenReturn(Optional.empty());

        servicio.solicitar("nadie@correo.pe");

        verify(enviador, never()).enviar(anyString(), anyString(), anyString(), anyString());
        verify(valores, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Un segundo pedido antes de 60 segundos no envía otro correo")
    void esperaEntreEnvios() {
        when(valores.setIfAbsent(eq(CLAVE + ":espera"), anyString(), any(Duration.class))).thenReturn(false);

        servicio.solicitar(CORREO);

        verify(enviador, never()).enviar(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Sin proveedor de correo configurado responde 503")
    void sinCorreo() {
        when(enviador.activo()).thenReturn(false);

        assertThatThrownBy(() -> servicio.solicitar(CORREO))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    // ---------------------------------------------------------------- cambiar contraseña

    @Test
    @DisplayName("Con el código correcto cambia la contraseña, cierra todas las sesiones y borra el código")
    void restablece() {
        when(valores.get(CLAVE + ":codigo")).thenReturn(jwt.hashearToken("482913"));
        when(valores.increment(CLAVE + ":intentos")).thenReturn(1L);

        servicio.restablecer(CORREO, "482913", "NuevaClave123");

        assertThat(lucia.getPasswordHash()).isEqualTo("hash(NuevaClave123)");
        verify(usuarios).save(lucia);
        verify(revocacion).revocarTodasLasSesionesDelUsuario(lucia.getId());
        verify(limiteLogin).limpiar(CORREO);
        verify(redis).delete(java.util.List.of(CLAVE + ":codigo", CLAVE + ":intentos"));
    }

    @Test
    @DisplayName("Con un código incorrecto responde 400 y la contraseña no cambia")
    void codigoIncorrecto() {
        when(valores.get(CLAVE + ":codigo")).thenReturn(jwt.hashearToken("482913"));
        when(valores.increment(CLAVE + ":intentos")).thenReturn(1L);

        assertThatThrownBy(() -> servicio.restablecer(CORREO, "111111", "NuevaClave123"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(lucia.getPasswordHash()).isEqualTo("hash-viejo");
        verify(revocacion, never()).revocarTodasLasSesionesDelUsuario(any());
    }

    @Test
    @DisplayName("Si el código venció (ya no está en Redis) responde 400")
    void codigoVencido() {
        when(valores.get(CLAVE + ":codigo")).thenReturn(null);

        assertThatThrownBy(() -> servicio.restablecer(CORREO, "482913", "NuevaClave123"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("venció");
        verify(usuarios, never()).save(any());
    }

    @Test
    @DisplayName("Al sexto intento el código se anula aunque sea el correcto")
    void demasiadosIntentos() {
        when(valores.get(CLAVE + ":codigo")).thenReturn(jwt.hashearToken("482913"));
        when(valores.increment(CLAVE + ":intentos")).thenReturn(6L);

        assertThatThrownBy(() -> servicio.restablecer(CORREO, "482913", "NuevaClave123"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Demasiados intentos");
        verify(redis).delete(java.util.List.of(CLAVE + ":codigo", CLAVE + ":intentos"));
        verify(usuarios, never()).save(any());
    }

    @Test
    @DisplayName("Si Redis no responde, responde 503 en vez de un error 500")
    void redisCaido() {
        when(valores.get(anyString())).thenThrow(new RedisConnectionFailureException("sin conexión"));

        assertThatThrownBy(() -> servicio.restablecer(CORREO, "482913", "NuevaClave123"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}

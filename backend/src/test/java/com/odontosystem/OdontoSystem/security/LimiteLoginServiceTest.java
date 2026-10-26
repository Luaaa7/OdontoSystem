package com.odontosystem.OdontoSystem.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Simula Redis con un mapa en memoria. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LimiteLoginServiceTest {

    private static final String CORREO = "Lucia@Correo.pe";
    private static final String IP = "190.12.3.4";

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valores;

    private final Map<String, Long> contadores = new HashMap<>();
    private LimiteLoginService limite;

    @BeforeEach
    void setUp() {
        limite = new LimiteLoginService(redis, 5, 20, 15);
        when(redis.opsForValue()).thenReturn(valores);
        when(valores.increment(anyString())).thenAnswer(i -> contadores.merge(i.getArgument(0), 1L, Long::sum));
        when(valores.get(anyString())).thenAnswer(i -> {
            Long v = contadores.get((String) i.getArgument(0));
            return v == null ? null : v.toString();
        });
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(600L);
        when(redis.delete(anyString())).thenAnswer(i -> contadores.remove((String) i.getArgument(0)) != null);
    }

    @Test
    @DisplayName("Cuenta los intentos que le quedan y al quinto fallo bloquea la cuenta (429 con los segundos restantes)")
    void bloqueaAlQuintoFallo() {
        assertThat(limite.registrarFallo(CORREO, IP)).isEqualTo(4);
        limite.registrarFallo(CORREO, IP);
        limite.registrarFallo(CORREO, IP);
        assertThat(limite.registrarFallo(CORREO, IP)).isEqualTo(1);
        assertThatCode(() -> limite.verificar(CORREO, IP)).doesNotThrowAnyException();

        assertThat(limite.registrarFallo(CORREO, IP)).isZero();
        assertThatThrownBy(() -> limite.verificar(CORREO, IP))
                .isInstanceOf(LoginBloqueadoException.class)
                .satisfies(e -> assertThat(((LoginBloqueadoException) e).getSegundosRestantes()).isEqualTo(600))
                .hasMessageContaining("10 minuto");
    }

    @Test
    @DisplayName("El correo no distingue mayúsculas: Lucia@ y lucia@ comparten contador")
    void correoSinMayusculas() {
        for (int i = 0; i < 5; i++) limite.registrarFallo(i % 2 == 0 ? CORREO : CORREO.toLowerCase(), IP);
        assertThatThrownBy(() -> limite.verificar(" lucia@correo.pe ", "otra-ip")).isInstanceOf(LoginBloqueadoException.class);
    }

    @Test
    @DisplayName("Un login correcto borra el contador de la cuenta")
    void loginCorrectoLimpia() {
        limite.registrarFallo(CORREO, IP);
        limite.registrarFallo(CORREO, IP);
        limite.limpiar(CORREO);
        assertThat(limite.registrarFallo(CORREO, IP)).isEqualTo(4);
    }

    @Test
    @DisplayName("20 fallos desde la misma IP con correos distintos bloquean esa IP")
    void bloqueaIp() {
        for (int i = 0; i < 20; i++) limite.registrarFallo("persona" + i + "@correo.pe", IP);
        assertThatThrownBy(() -> limite.verificar("nueva@correo.pe", IP)).isInstanceOf(LoginBloqueadoException.class);
        assertThatCode(() -> limite.verificar("nueva@correo.pe", "200.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("El primer fallo abre la ventana de 15 minutos")
    void abreVentana() {
        limite.registrarFallo(CORREO, IP);
        verify(redis).expire(LimiteLoginService.claveCuenta(CORREO), Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("Si Redis se cae, el login sigue funcionando sin límite")
    void redisCaido() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("sin conexión"));
        assertThatCode(() -> limite.verificar(CORREO, IP)).doesNotThrowAnyException();
        assertThat(limite.registrarFallo(CORREO, IP)).isEqualTo(-1);
    }

    @Test
    @DisplayName("En los logs el correo va enmascarado")
    void ocultaCorreo() {
        assertThat(LimiteLoginService.ocultar("lucia@correo.pe")).isEqualTo("l***@correo.pe");
    }
}

package com.odontosystem.OdontoSystem.notificaciones;

import com.odontosystem.OdontoSystem.entity.DispositivoUsuario;
import com.odontosystem.OdontoSystem.notificaciones.EnviadorPush.Resultado;
import com.odontosystem.OdontoSystem.notificaciones.PushService.DispositivoRequest;
import com.odontosystem.OdontoSystem.repository.DispositivoUsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Notificaciones push: registro de celulares y envío a todos los del usuario. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushServiceTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-20T15:00:00Z"), ZoneOffset.UTC);

    @Mock private DispositivoUsuarioRepository dispositivos;
    @Mock private EnviadorPush enviador;

    private PushService servicio;
    private final UUID lucia = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        servicio = new PushService(dispositivos, enviador, RELOJ);
        when(enviador.activo()).thenReturn(true);
        when(dispositivos.findByUsuarioIdAndTokenFcm(any(), anyString())).thenReturn(Optional.empty());
        when(dispositivos.findByUsuarioId(any())).thenReturn(List.of());
    }

    private DispositivoUsuario celular(String token, int horasSinUso) {
        return DispositivoUsuario.builder().id((long) token.hashCode()).usuarioId(lucia).tokenFcm(token)
                .ultimaActividadEn(OffsetDateTime.now(RELOJ).minusHours(horasSinUso)).build();
    }

    @Test
    @DisplayName("Registrar un celular nuevo guarda su token y sistema; el sistema se normaliza a mayúsculas")
    void registra() {
        servicio.registrar(lucia, new DispositivoRequest(" token-a ", "android"));

        ArgumentCaptor<DispositivoUsuario> guardado = ArgumentCaptor.forClass(DispositivoUsuario.class);
        verify(dispositivos).save(guardado.capture());
        assertThat(guardado.getValue().getTokenFcm()).isEqualTo("token-a");
        assertThat(guardado.getValue().getSistemaOperativo()).isEqualTo("ANDROID");
        assertThat(guardado.getValue().getUltimaActividadEn()).isEqualTo(OffsetDateTime.now(RELOJ));
    }

    @Test
    @DisplayName("Con 10 celulares registrados, el nuevo reemplaza al que lleva más tiempo sin usarse")
    void maximoDeCelulares() {
        List<DispositivoUsuario> actuales = new ArrayList<>();
        for (int i = 0; i < 10; i++) actuales.add(celular("t" + i, i));
        when(dispositivos.findByUsuarioId(lucia)).thenReturn(actuales);

        servicio.registrar(lucia, new DispositivoRequest("nuevo", "WEB"));

        verify(dispositivos).delete(actuales.get(9));
    }

    @Test
    @DisplayName("Sistema operativo desconocido → 400")
    void sistemaInvalido() {
        assertThatThrownBy(() -> servicio.registrar(lucia, new DispositivoRequest("t", "WINDOWS")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Envía a todos los celulares del usuario y borra el token que Firebase da por inválido")
    void envia() {
        when(dispositivos.findByUsuarioId(lucia)).thenReturn(List.of(celular("bueno", 1), celular("viejo", 2)));
        when(enviador.enviar("bueno", "Hola", "Cuerpo", Map.of())).thenReturn(Resultado.ENVIADO);
        when(enviador.enviar("viejo", "Hola", "Cuerpo", Map.of())).thenReturn(Resultado.INVALIDO);

        assertThat(servicio.enviarAUsuario(lucia, "Hola", "Cuerpo", Map.of())).isEqualTo(1);
        verify(dispositivos).borrarToken("viejo");
    }

    @Test
    @DisplayName("Sin Firebase configurado no intenta enviar")
    void sinFirebase() {
        when(enviador.activo()).thenReturn(false);
        assertThat(servicio.enviarAUsuario(lucia, "Hola", "Cuerpo", Map.of())).isZero();
        verify(enviador, never()).enviar(anyString(), anyString(), anyString(), any());
    }
}

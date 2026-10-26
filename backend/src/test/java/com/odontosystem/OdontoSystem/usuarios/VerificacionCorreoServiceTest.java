package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.entity.CanalNotificacion;
import com.odontosystem.OdontoSystem.entity.OtpVerificacion;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.notificaciones.EnviadorCorreo;
import com.odontosystem.OdontoSystem.notificaciones.PlantillasCorreo;
import com.odontosystem.OdontoSystem.repository.OtpVerificacionRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OTP por correo. Reloj fijo: 24/10/2026 12:00 UTC. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerificacionCorreoServiceTest {

    private static final Instant AHORA_INSTANTE = Instant.parse("2026-10-24T12:00:00Z");
    private static final OffsetDateTime AHORA = AHORA_INSTANTE.atOffset(ZoneOffset.UTC);

    @Mock private UsuarioRepository usuarios;
    @Mock private OtpVerificacionRepository otps;
    @Mock private EnviadorCorreo enviador;
    @Mock private PlantillasCorreo plantillas;

    private VerificacionCorreoService servicio;
    private Usuario usuario;

    @BeforeEach
    void setUp() {
        servicio = servicio(true);
        usuario = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Luis Paredes Soto")
                .correo("luis@correo.pe").rol(RolUsuario.PACIENTE).estaActivo(true).build();
        when(usuarios.findById(usuario.getId())).thenReturn(Optional.of(usuario));
        when(usuarios.findByCorreo("luis@correo.pe")).thenReturn(Optional.of(usuario));
        when(enviador.activo()).thenReturn(true);
        when(plantillas.armar(anyString(), anyMap())).thenReturn("<html>");
        when(otps.save(any(OtpVerificacion.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private VerificacionCorreoService servicio(boolean obligatoria) {
        return new VerificacionCorreoService(usuarios, otps, enviador, plantillas,
                Clock.fixed(AHORA_INSTANTE, ZoneOffset.UTC), obligatoria);
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    private OtpVerificacion codigoVigente(String codigo, int intentos, OffsetDateTime expira) {
        OtpVerificacion otp = OtpVerificacion.builder().id(UUID.randomUUID()).usuarioId(usuario.getId())
                .destino("luis@correo.pe").canal(CanalNotificacion.EMAIL)
                .codigoHash(VerificacionCorreoService.sha256(codigo)).intentos((short) intentos)
                .creadoEn(AHORA.minusMinutes(2)).expiraEn(expira).build();
        when(otps.findFirstByUsuarioIdAndConsumidoEnIsNullOrderByCreadoEnDesc(usuario.getId())).thenReturn(Optional.of(otp));
        return otp;
    }

    @Test
    @DisplayName("Pedir código: guarda solo el hash, vence en 15 min y manda el mismo código por correo")
    @SuppressWarnings("unchecked")
    void enviaCodigo() {
        servicio.enviarCodigo(usuario.getId());

        ArgumentCaptor<OtpVerificacion> guardado = ArgumentCaptor.forClass(OtpVerificacion.class);
        verify(otps).save(guardado.capture());
        ArgumentCaptor<Map<String, Object>> datos = ArgumentCaptor.forClass(Map.class);
        verify(plantillas).armar(eq("verificar-correo"), datos.capture());
        String codigo = (String) datos.getValue().get("codigo");
        assertThat(codigo).matches("\\d{6}");
        assertThat(datos.getValue().get("nombre")).isEqualTo("Luis");
        OtpVerificacion otp = guardado.getValue();
        assertThat(otp.getCodigoHash()).isEqualTo(VerificacionCorreoService.sha256(codigo)).doesNotContain(codigo);
        assertThat(otp.getDestino()).isEqualTo("luis@correo.pe");
        assertThat(otp.getCanal()).isEqualTo(CanalNotificacion.EMAIL);
        assertThat(otp.getExpiraEn()).isEqualTo(AHORA.plusMinutes(15));
        verify(enviador).enviar(eq("luis@correo.pe"), any(), eq("Tu código para verificar tu correo"), eq("<html>"));
    }

    @Test
    @DisplayName("Si ya pidió un código hace menos de 1 minuto → 429 y no se manda otro")
    void unoPorMinuto() {
        when(otps.findFirstByDestinoIgnoreCaseOrderByCreadoEnDesc("luis@correo.pe")).thenReturn(Optional.of(
                OtpVerificacion.builder().creadoEn(AHORA.minusSeconds(30)).build()));

        assertThatThrownBy(() -> servicio.enviarCodigo(usuario.getId()))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(429));
        verify(enviador, never()).enviar(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Correo ya verificado → 409; sin proveedor de correo → 503")
    void yaVerificadoOSinCorreo() {
        usuario.setEmailVerificadoEn(AHORA.minusDays(1));
        assertThatThrownBy(() -> servicio.enviarCodigo(usuario.getId()))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(409));

        usuario.setEmailVerificadoEn(null);
        when(enviador.activo()).thenReturn(false);
        assertThatThrownBy(() -> servicio.enviarCodigo(usuario.getId()))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(503));
    }

    @Test
    @DisplayName("Si el proveedor de correo falla → 503")
    void proveedorFalla() {
        doThrow(new IllegalStateException("sin red")).when(enviador).enviar(any(), any(), any(), any());

        assertThatThrownBy(() -> servicio.enviarCodigo(usuario.getId()))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(503));
    }

    @Test
    @DisplayName("Código correcto: el correo queda verificado y el código ya no se puede volver a usar")
    void verificaOk() {
        OtpVerificacion otp = codigoVigente("483920", 0, AHORA.plusMinutes(10));

        var r = servicio.verificar(usuario.getId(), " 483920 ");

        assertThat(r.correoVerificado()).isTrue();
        assertThat(usuario.getEmailVerificadoEn()).isEqualTo(AHORA);
        assertThat(otp.getConsumidoEn()).isEqualTo(AHORA);
        verify(usuarios).save(usuario);
    }

    @Test
    @DisplayName("Código incorrecto → 400 y suma un intento")
    void codigoIncorrecto() {
        OtpVerificacion otp = codigoVigente("483920", 2, AHORA.plusMinutes(10));

        assertThatThrownBy(() -> servicio.verificar(usuario.getId(), "111111"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400))
                .hasMessageContaining("no es válido");
        assertThat(otp.getIntentos()).isEqualTo((short) 3);
        assertThat(usuario.getEmailVerificadoEn()).isNull();
    }

    @Test
    @DisplayName("Con 5 intentos fallidos el código se anula, aunque ahora pongan el correcto")
    void demasiadosIntentos() {
        codigoVigente("483920", 5, AHORA.plusMinutes(10));

        assertThatThrownBy(() -> servicio.verificar(usuario.getId(), "483920"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400))
                .hasMessageContaining("Demasiados intentos");
        assertThat(usuario.getEmailVerificadoEn()).isNull();
    }

    @Test
    @DisplayName("Código vencido o sin pedir → 400")
    void vencido() {
        codigoVigente("483920", 0, AHORA.minusMinutes(1));

        assertThatThrownBy(() -> servicio.verificar(usuario.getId(), "483920"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
    }

    @Test
    @DisplayName("Reservar: 403 sin el correo verificado; pasa si ya lo verificó")
    void exigirVerificado() {
        assertThatThrownBy(() -> servicio.exigirVerificado(usuario.getId()))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(403))
                .hasMessageContaining("Verifica tu correo");

        usuario.setEmailVerificadoEn(AHORA);
        assertThatCode(() -> servicio.exigirVerificado(usuario.getId())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("No se exige si la verificación está apagada o si no hay proveedor de correo")
    void noSeExige() {
        assertThatCode(() -> servicio(false).exigirVerificado(usuario.getId())).doesNotThrowAnyException();

        when(enviador.activo()).thenReturn(false);
        assertThatCode(() -> servicio.exigirVerificado(usuario.getId())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Al registrarse: si el correo no sale, el registro no falla")
    void registroNoFalla() {
        doThrow(new IllegalStateException("sin red")).when(enviador).enviar(any(), any(), any(), any());

        assertThatCode(() -> servicio.enviarAlRegistrarse("luis@correo.pe")).doesNotThrowAnyException();
    }
}

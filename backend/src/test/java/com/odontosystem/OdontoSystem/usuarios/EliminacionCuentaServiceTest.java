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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Eliminación de cuenta. Reloj fijo: 25/10/2026 12:00 UTC. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EliminacionCuentaServiceTest {

    private static final Instant INSTANTE = Instant.parse("2026-10-25T12:00:00Z");
    private static final OffsetDateTime AHORA = INSTANTE.atOffset(ZoneOffset.UTC);

    @Mock private UsuarioRepository usuarios;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JdbcTemplate jdbc;
    @Mock private CitaRepository citas;
    @Mock private CitaService citaService;
    @Mock private RevocacionSesionesService revocacion;
    @Mock private OtpVerificacionRepository otps;
    @Mock private EnviadorCorreo enviador;
    @Mock private PlantillasCorreo plantillas;

    private EliminacionCuentaService servicio;
    private Usuario paciente;

    @BeforeEach
    void setUp() {
        servicio = new EliminacionCuentaService(usuarios, passwordEncoder, jdbc, citas, citaService, revocacion, otps,
                enviador, plantillas, Clock.fixed(INSTANTE, ZoneOffset.UTC));
        paciente = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Luis Paredes").correo("luis@correo.pe")
                .telefono("987654321").numeroDocumento("44556677").rol(RolUsuario.PACIENTE)
                .passwordHash("$2a$10$hash").googleOauthSub("google-123").fotoPerfilUrl("https://foto")
                .emailVerificadoEn(AHORA).estaActivo(true).build();
        when(usuarios.findById(paciente.getId())).thenReturn(Optional.of(paciente));
        when(usuarios.findByCorreo("luis@correo.pe")).thenReturn(Optional.of(paciente));
        when(passwordEncoder.matches("Secreta123!", "$2a$10$hash")).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$nueva");
        when(citas.delPaciente(paciente.getId())).thenReturn(List.of());
        when(enviador.activo()).thenReturn(true);
        when(plantillas.armar(anyString(), anyMap())).thenReturn("<html>");
        when(otps.save(any(OtpVerificacion.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static int estado(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    private Cita cita(EstadoCita estado, OffsetDateTime inicio) {
        return Cita.builder().id(UUID.randomUUID()).paciente(paciente).estado(estado).inicioProgramado(inicio).build();
    }

    @Test
    @DisplayName("Con contraseña y ELIMINAR: anonimiza los datos personales y desactiva la cuenta")
    void eliminaYAnonimiza() {
        servicio.eliminarMiCuenta(paciente.getId(), "Secreta123!", "eliminar");

        assertThat(paciente.getEstaActivo()).isFalse();
        assertThat(paciente.getNombreCompleto()).isEqualTo("Usuario eliminado");
        assertThat(paciente.getCorreo()).startsWith("eliminado-").endsWith("@odontosystem.invalid");
        assertThat(paciente.getTelefono()).startsWith("X").hasSize(20);
        assertThat(paciente.getNumeroDocumento()).startsWith("X").hasSize(20);
        assertThat(paciente.getPasswordHash()).isEqualTo("$2a$10$nueva");
        assertThat(paciente.getGoogleOauthSub()).isNull();
        assertThat(paciente.getFotoPerfilUrl()).isNull();
        assertThat(paciente.getEmailVerificadoEn()).isNull();
        verify(usuarios).save(paciente);
        verify(revocacion).revocarTodasLasSesionesDelUsuario(paciente.getId());
        verify(jdbc).update(contains("dispositivos_usuario"), eq(paciente.getId()));
        verify(jdbc).update(contains("sesiones_chat"), eq(paciente.getId()));
        verify(jdbc).update(contains("otp_verificaciones"), eq(paciente.getId()));
    }

    @Test
    @DisplayName("Cancela solo las citas futuras que siguen activas")
    void cancelaCitasFuturas() {
        Cita futura = cita(EstadoCita.CONFIRMADA, AHORA.plusDays(3));
        Cita pasada = cita(EstadoCita.CONFIRMADA, AHORA.minusDays(3));
        Cita completada = cita(EstadoCita.COMPLETADA, AHORA.plusDays(1));
        when(citas.delPaciente(paciente.getId())).thenReturn(List.of(futura, pasada, completada));

        servicio.eliminarMiCuenta(paciente.getId(), "Secreta123!", "ELIMINAR");

        verify(citaService).cancelar(paciente.getId(), futura.getId(), "El paciente eliminó su cuenta");
        verify(citaService, never()).cancelar(any(), eq(pasada.getId()), any());
        verify(citaService, never()).cancelar(any(), eq(completada.getId()), any());
    }

    @Test
    @DisplayName("Sin escribir ELIMINAR o con la contraseña incorrecta → 400 y no se toca nada")
    void confirmacionYContrasena() {
        assertThatThrownBy(() -> servicio.eliminarMiCuenta(paciente.getId(), "Secreta123!", "si"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
        assertThatThrownBy(() -> servicio.eliminarMiCuenta(paciente.getId(), "otra", "ELIMINAR"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400))
                .hasMessageContaining("contraseña");
        assertThat(paciente.getEstaActivo()).isTrue();
        verify(usuarios, never()).save(any());
    }

    @Test
    @DisplayName("Una cuenta solo de Google (sin contraseña) se elimina con la confirmación")
    void cuentaDeGoogle() {
        paciente.setPasswordHash(null);

        servicio.eliminarMiCuenta(paciente.getId(), null, "ELIMINAR");

        assertThat(paciente.getEstaActivo()).isFalse();
    }

    @Test
    @DisplayName("Odontólogo: desactiva su perfil y servicios y cancela su agenda futura")
    void odontologo() {
        paciente.setRol(RolUsuario.ODONTOLOGO);
        Cita agendada = cita(EstadoCita.CONFIRMADA, AHORA.plusDays(2));
        when(citas.agenda(eq(paciente.getId()), any(), any())).thenReturn(List.of(agendada));

        servicio.eliminarMiCuenta(paciente.getId(), "Secreta123!", "ELIMINAR");

        verify(citaService).cancelar(paciente.getId(), agendada.getId(), "El odontólogo dejó la plataforma");
        verify(jdbc).update(contains("perfiles_odontologo"), eq(AHORA), eq(paciente.getId()));
        verify(jdbc).update(contains("servicios"), eq(paciente.getId()));
    }

    @Test
    @DisplayName("Web: pedir código guarda solo el hash y manda el correo; un correo sin cuenta no revela nada")
    @SuppressWarnings("unchecked")
    void pedirCodigoWeb() {
        servicio.solicitarCodigo("luis@correo.pe");

        ArgumentCaptor<OtpVerificacion> otp = ArgumentCaptor.forClass(OtpVerificacion.class);
        verify(otps).save(otp.capture());
        ArgumentCaptor<Map<String, Object>> datos = ArgumentCaptor.forClass(Map.class);
        verify(plantillas).armar(eq("eliminar-cuenta"), datos.capture());
        String codigo = (String) datos.getValue().get("codigo");
        assertThat(otp.getValue().getCodigoHash()).isEqualTo(VerificacionCorreoService.sha256(codigo));
        assertThat(otp.getValue().getDestino()).isEqualTo("baja:luis@correo.pe");
        assertThat(otp.getValue().getCanal()).isEqualTo(CanalNotificacion.EMAIL);

        assertThatCode(() -> servicio.solicitarCodigo("nadie@correo.pe")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Web: con el código correcto se elimina; con uno incorrecto → 400 y suma un intento")
    void eliminarConCodigoWeb() {
        OtpVerificacion otp = OtpVerificacion.builder().usuarioId(paciente.getId()).destino("baja:luis@correo.pe")
                .canal(CanalNotificacion.EMAIL).codigoHash(VerificacionCorreoService.sha256("135790"))
                .intentos((short) 0).creadoEn(AHORA.minusMinutes(1)).expiraEn(AHORA.plusMinutes(14)).build();
        when(otps.findFirstByDestinoIgnoreCaseOrderByCreadoEnDesc("baja:luis@correo.pe")).thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> servicio.eliminarConCodigo("luis@correo.pe", "111111"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
        assertThat(otp.getIntentos()).isEqualTo((short) 1);
        assertThat(paciente.getEstaActivo()).isTrue();

        servicio.eliminarConCodigo("luis@correo.pe", "135790");

        assertThat(paciente.getEstaActivo()).isFalse();
        assertThat(otp.getConsumidoEn()).isEqualTo(AHORA);
    }

    @Test
    @DisplayName("Web: código vencido → 400")
    void codigoVencido() {
        OtpVerificacion otp = OtpVerificacion.builder().usuarioId(paciente.getId()).destino("baja:luis@correo.pe")
                .canal(CanalNotificacion.EMAIL).codigoHash(VerificacionCorreoService.sha256("135790"))
                .intentos((short) 0).creadoEn(AHORA.minusMinutes(20)).expiraEn(AHORA.minusMinutes(5)).build();
        when(otps.findFirstByDestinoIgnoreCaseOrderByCreadoEnDesc("baja:luis@correo.pe")).thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> servicio.eliminarConCodigo("luis@correo.pe", "135790"))
                .satisfies(e -> assertThat(estado(e)).isEqualTo(400));
        assertThat(paciente.getEstaActivo()).isTrue();
    }
}

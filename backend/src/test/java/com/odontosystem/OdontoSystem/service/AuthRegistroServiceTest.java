package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.dto.AuthResponse;
import com.odontosystem.OdontoSystem.dto.RegistroRequest;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.ReputacionPaciente;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ReputacionPacienteRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import com.odontosystem.OdontoSystem.security.JwtService;
import com.odontosystem.OdontoSystem.security.LimiteLoginService;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import com.odontosystem.OdontoSystem.usuarios.ConsentimientoService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Registro de cuentas y renovación de la sesión (el login ya se prueba en AuthLoginServiceTest). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthRegistroServiceTest {

    private static final String IP = "190.12.3.4";

    @Mock private UsuarioRepository usuarios;
    @Mock private PasswordEncoder encoder;
    @Mock private AuthenticationManager authManager;
    @Mock private JwtService jwt;
    @Mock private TokenRefrescoService tokens;
    @Mock private PerfilOdontologoRepository perfiles;
    @Mock private ReputacionPacienteRepository reputaciones;
    @Mock private VerificacionColegiaturaService verificacionColegiatura;
    @Mock private LimiteLoginService limite;
    @Mock private ConsentimientoService consentimientos;
    @Mock private HttpServletRequest http;

    private AuthService servicio;
    private final UUID idNuevo = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        servicio = new AuthService(usuarios, encoder, authManager, jwt, tokens, perfiles, reputaciones,
                verificacionColegiatura, limite, consentimientos);
        when(http.getRemoteAddr()).thenReturn(IP);
        when(encoder.encode(anyString())).thenAnswer(i -> "hash(" + i.getArgument(0) + ")");
        when(usuarios.save(any(Usuario.class))).thenAnswer(i -> {
            Usuario u = i.getArgument(0);
            u.setId(idNuevo);
            return u;
        });
        when(jwt.generateAccessToken(any(UsuarioPrincipal.class))).thenReturn("access");
        when(tokens.crearParaUsuario(any(Usuario.class), any())).thenReturn("refresh");
    }

    @Test
    @DisplayName("Registro de paciente: guarda la contraseña con hash, su reputación, el consentimiento y entrega los tokens")
    void registraPaciente() {
        AuthResponse r = servicio.registrar(registro(RolUsuario.PACIENTE, null), http);

        ArgumentCaptor<Usuario> guardado = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarios).save(guardado.capture());
        assertThat(guardado.getValue().getPasswordHash()).isEqualTo("hash(Secreta123)").isNotEqualTo("Secreta123");
        assertThat(guardado.getValue().getRol()).isEqualTo(RolUsuario.PACIENTE);

        verify(consentimientos).registrar(idNuevo, IP);
        verify(reputaciones).save(any(ReputacionPaciente.class));
        verify(verificacionColegiatura, never()).verificarYGuardar(any());
        assertThat(r.accessToken()).isEqualTo("access");
        assertThat(r.refreshToken()).isEqualTo("refresh");
        assertThat(r.tokenType()).isEqualTo("Bearer");
        assertThat(r.rol()).isEqualTo("PACIENTE");
    }

    @Test
    @DisplayName("Registro de odontólogo: crea su perfil y lo manda a verificar en el COP")
    void registraOdontologo() {
        servicio.registrar(registro(RolUsuario.ODONTOLOGO, "01000"), http);

        ArgumentCaptor<PerfilOdontologo> perfil = ArgumentCaptor.forClass(PerfilOdontologo.class);
        verify(verificacionColegiatura).verificarYGuardar(perfil.capture());
        assertThat(perfil.getValue().getNumeroColegiatura()).isEqualTo("01000");
        verify(reputaciones, never()).save(any());
        verify(consentimientos).registrar(idNuevo, IP);
    }

    @Test
    @DisplayName("Correo, documento o teléfono repetidos responden 409 y no crean la cuenta")
    void duplicados() {
        when(usuarios.existsByCorreo("lucia@correo.pe")).thenReturn(true);
        esperar409("correo");

        when(usuarios.existsByCorreo("lucia@correo.pe")).thenReturn(false);
        when(usuarios.existsByNumeroDocumento("70123456")).thenReturn(true);
        esperar409("documento");

        when(usuarios.existsByNumeroDocumento("70123456")).thenReturn(false);
        when(usuarios.existsByTelefono("987654321")).thenReturn(true);
        esperar409("teléfono");

        verify(usuarios, never()).save(any());
    }

    @Test
    @DisplayName("Un odontólogo sin número de colegiatura recibe 400")
    void odontologoSinCop() {
        assertThatThrownBy(() -> servicio.registrar(registro(RolUsuario.ODONTOLOGO, "  "), http))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(usuarios, never()).save(any());
    }

    @Test
    @DisplayName("Un número de colegiatura ya usado por otra cuenta recibe 409")
    void copRepetido() {
        when(perfiles.existsByNumeroColegiatura("01000")).thenReturn(true);

        assertThatThrownBy(() -> servicio.registrar(registro(RolUsuario.ODONTOLOGO, "01000"), http))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(usuarios, never()).save(any());
    }

    @Test
    @DisplayName("Renovar la sesión rota el refresh token y entrega un access token nuevo")
    void refrescar() {
        Usuario lucia = Usuario.builder().id(idNuevo).correo("lucia@correo.pe").nombreCompleto("Lucía Ramos")
                .rol(RolUsuario.PACIENTE).build();
        when(tokens.rotar("viejo", http)).thenReturn(new TokenRefrescoService.ResultadoRotacion(lucia, "nuevo"));

        AuthResponse r = servicio.refrescar("viejo", http);

        assertThat(r.accessToken()).isEqualTo("access");
        assertThat(r.refreshToken()).isEqualTo("nuevo");
        assertThat(r.nombreCompleto()).isEqualTo("Lucía Ramos");
    }

    private void esperar409(String campo) {
        assertThatThrownBy(() -> servicio.registrar(registro(RolUsuario.PACIENTE, null), http))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).contains(campo);
                });
    }

    private static RegistroRequest registro(RolUsuario rol, String cop) {
        return new RegistroRequest("70123456", "DNI", "Lucía Ramos", "987654321", "lucia@correo.pe",
                "Secreta123", rol, cop, true);
    }
}

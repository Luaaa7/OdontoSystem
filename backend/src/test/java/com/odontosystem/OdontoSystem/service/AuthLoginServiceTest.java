package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.dto.AuthResponse;
import com.odontosystem.OdontoSystem.dto.LoginRequest;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.ReputacionPacienteRepository;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import com.odontosystem.OdontoSystem.repository.VerificacionCopRepository;
import com.odontosystem.OdontoSystem.security.JwtService;
import com.odontosystem.OdontoSystem.security.LimiteLoginService;
import com.odontosystem.OdontoSystem.security.LoginBloqueadoException;
import com.odontosystem.OdontoSystem.security.UsuarioPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Login con límite de intentos (el resto de AuthService no cambia). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthLoginServiceTest {

    private static final String IP = "190.12.3.4";

    @Mock private UsuarioRepository usuarios;
    @Mock private PasswordEncoder encoder;
    @Mock private AuthenticationManager authManager;
    @Mock private JwtService jwt;
    @Mock private TokenRefrescoService tokens;
    @Mock private PerfilOdontologoRepository perfiles;
    @Mock private ReputacionPacienteRepository reputaciones;
    @Mock private VerificacionCopRepository verificaciones;
    @Mock private ColegioOdontologicoVerificacionService cop;
    @Mock private com.odontosystem.OdontoSystem.service.VerificacionColegiaturaService verificacionColegiatura;
    @Mock private LimiteLoginService limite;
    @Mock private com.odontosystem.OdontoSystem.usuarios.ConsentimientoService consentimientos;
    @Mock private HttpServletRequest http;

    private AuthService servicio;
    private final LoginRequest login = new LoginRequest("lucia@correo.pe", "secreta");

    @BeforeEach
    void setUp() {
        servicio = new AuthService(usuarios, encoder, authManager, jwt, tokens, perfiles, reputaciones, verificacionColegiatura, limite,
                consentimientos);
        when(http.getRemoteAddr()).thenReturn(IP);
        when(limite.segundosDeBloqueo()).thenReturn(900L);
    }

    @Test
    @DisplayName("Login correcto: entrega los tokens y borra el contador de fallos")
    void loginCorrecto() {
        Usuario u = Usuario.builder().id(UUID.randomUUID()).correo("lucia@correo.pe").nombreCompleto("Lucía Ramos")
                .rol(RolUsuario.PACIENTE).build();
        UsuarioPrincipal principal = new UsuarioPrincipal(u);
        when(authManager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        when(jwt.generateAccessToken(principal)).thenReturn("access");
        when(tokens.crearParaUsuario(u, http)).thenReturn("refresh");

        AuthResponse r = servicio.login(login, http);

        assertThat(r.accessToken()).isEqualTo("access");
        verify(limite).verificar("lucia@correo.pe", IP);
        verify(limite).limpiar("lucia@correo.pe");
    }

    @Test
    @DisplayName("Cuenta bloqueada: responde 429 sin revisar la contraseña")
    void bloqueadaNoRevisaContrasena() {
        doThrow(new LoginBloqueadoException(600)).when(limite).verificar("lucia@correo.pe", IP);
        assertThatThrownBy(() -> servicio.login(login, http)).isInstanceOf(LoginBloqueadoException.class);
        verify(authManager, never()).authenticate(any());
    }

    @Test
    @DisplayName("Contraseña incorrecta con varios intentos por delante: el 401 de siempre")
    void falloNormal() {
        when(authManager.authenticate(any())).thenThrow(new BadCredentialsException("x"));
        when(limite.registrarFallo("lucia@correo.pe", IP)).thenReturn(4);
        assertThatThrownBy(() -> servicio.login(login, http)).isInstanceOf(BadCredentialsException.class);
        verify(limite, never()).limpiar(any());
    }

    @Test
    @DisplayName("Con pocos intentos restantes avisa cuántos quedan")
    void avisaIntentosRestantes() {
        when(authManager.authenticate(any())).thenThrow(new BadCredentialsException("x"));
        when(limite.registrarFallo("lucia@correo.pe", IP)).thenReturn(1);
        assertThatThrownBy(() -> servicio.login(login, http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("queda 1 intento");
    }

    @Test
    @DisplayName("El fallo que llega al límite ya responde 429")
    void ultimoFalloBloquea() {
        when(authManager.authenticate(any())).thenThrow(new BadCredentialsException("x"));
        when(limite.registrarFallo("lucia@correo.pe", IP)).thenReturn(0);
        assertThatThrownBy(() -> servicio.login(login, http))
                .isInstanceOf(LoginBloqueadoException.class)
                .satisfies(e -> assertThat(((LoginBloqueadoException) e).getSegundosRestantes()).isEqualTo(900));
    }
}

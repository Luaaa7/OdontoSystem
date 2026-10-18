package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.dto.AuthResponse;
import com.odontosystem.OdontoSystem.dto.GoogleRegistroRequest;
import com.odontosystem.OdontoSystem.entity.MetodoAutenticacion;
import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import com.odontosystem.OdontoSystem.security.VerificadorGoogle;
import com.odontosystem.OdontoSystem.security.VerificadorGoogle.DatosGoogle;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RF16: entrar y registrarse con Google. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthGoogleServiceTest {

    @Mock private VerificadorGoogle verificador;
    @Mock private UsuarioRepository usuarios;
    @Mock private AuthService authService;

    private AuthGoogleService servicio;
    private final MockHttpServletRequest http = new MockHttpServletRequest();
    private final DatosGoogle google = new DatosGoogle("google-sub-1", "lucia@gmail.com", true, "Lucía Ramos", "https://foto");
    private final AuthResponse tokens = new AuthResponse("access", "refresh", "PACIENTE", "Lucía Ramos");

    @BeforeEach
    void setUp() {
        servicio = new AuthGoogleService(verificador, usuarios, authService);
        when(verificador.verificar("token-google")).thenReturn(google);
        when(usuarios.findByGoogleOauthSub(anyString())).thenReturn(Optional.empty());
        when(usuarios.findByCorreo(anyString())).thenReturn(Optional.empty());
        when(usuarios.save(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));
        when(authService.emitirTokens(any(), any())).thenReturn(tokens);
        when(authService.crearCuenta(any(), any(), any())).thenReturn(tokens);
    }

    private Usuario lucia() {
        return Usuario.builder().id(UUID.randomUUID()).correo("lucia@gmail.com").nombreCompleto("Lucía Ramos")
                .rol(RolUsuario.PACIENTE).estaActivo(true).build();
    }

    // ---------------------------------------------------------------- entrar

    @Test
    @DisplayName("Si la cuenta de Google ya está ligada, entra y recibe sus tokens")
    void entraConCuentaLigada() {
        Usuario u = lucia();
        when(usuarios.findByGoogleOauthSub("google-sub-1")).thenReturn(Optional.of(u));

        assertThat(servicio.entrar("token-google", http)).isSameAs(tokens);
        verify(authService).emitirTokens(u, http);
    }

    @Test
    @DisplayName("Si existe una cuenta con el mismo correo (confirmado por Google), la liga y entra")
    void ligaPorCorreo() {
        Usuario u = lucia();
        when(usuarios.findByCorreo("lucia@gmail.com")).thenReturn(Optional.of(u));

        servicio.entrar("token-google", http);

        assertThat(u.getGoogleOauthSub()).isEqualTo("google-sub-1");
        assertThat(u.getEmailVerificadoEn()).isNotNull();
        verify(usuarios).save(u);
        verify(authService).emitirTokens(u, http);
    }

    @Test
    @DisplayName("No liga por correo si Google no confirma que el correo es de esa persona")
    void noLigaSinCorreoVerificado() {
        when(verificador.verificar("token-google"))
                .thenReturn(new DatosGoogle("google-sub-1", "lucia@gmail.com", false, "Lucía", null));
        when(usuarios.findByCorreo("lucia@gmail.com")).thenReturn(Optional.of(lucia()));

        assertThatThrownBy(() -> servicio.entrar("token-google", http))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(usuarios, never()).save(any());
    }

    @Test
    @DisplayName("Sin cuenta responde 404 para que el frontend muestre el registro")
    void sinCuenta() {
        assertThatThrownBy(() -> servicio.entrar("token-google", http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Completa tu registro");
    }

    @Test
    @DisplayName("Una cuenta desactivada no puede entrar con Google")
    void cuentaDesactivada() {
        Usuario u = lucia();
        u.setEstaActivo(false);
        when(usuarios.findByGoogleOauthSub("google-sub-1")).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> servicio.entrar("token-google", http))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(authService, never()).emitirTokens(any(), any());
    }

    // ---------------------------------------------------------------- registrarse

    private GoogleRegistroRequest registro(String nombre) {
        return new GoogleRegistroRequest("token-google", "70123456", "DNI", "987654321",
                RolUsuario.PACIENTE, null, nombre, true);
    }

    @Test
    @DisplayName("Registro con Google: correo, nombre y foto de Google, sin contraseña y con el correo ya verificado")
    void registra() {
        servicio.registrar(registro(null), http);

        verify(authService).validarDatosNuevos("lucia@gmail.com", "70123456", "987654321", RolUsuario.PACIENTE, null);
        ArgumentCaptor<Usuario> nuevo = ArgumentCaptor.forClass(Usuario.class);
        verify(authService).crearCuenta(nuevo.capture(), eq(null), eq(http));
        Usuario u = nuevo.getValue();
        assertThat(u.getCorreo()).isEqualTo("lucia@gmail.com");
        assertThat(u.getNombreCompleto()).isEqualTo("Lucía Ramos");
        assertThat(u.getGoogleOauthSub()).isEqualTo("google-sub-1");
        assertThat(u.getFotoPerfilUrl()).isEqualTo("https://foto");
        assertThat(u.getPasswordHash()).isNull();
        assertThat(u.getEmailVerificadoEn()).isNotNull();
        assertThat(u.getMetodoRegistro()).isEqualTo(MetodoAutenticacion.GOOGLE_OAUTH);
    }

    @Test
    @DisplayName("Si la persona escribe su nombre, se usa ese en vez del de Google")
    void usaNombreEscrito() {
        servicio.registrar(registro("  Lucía Ramos Pérez "), http);

        ArgumentCaptor<Usuario> nuevo = ArgumentCaptor.forClass(Usuario.class);
        verify(authService).crearCuenta(nuevo.capture(), any(), any());
        assertThat(nuevo.getValue().getNombreCompleto()).isEqualTo("Lucía Ramos Pérez");
    }

    @Test
    @DisplayName("Una cuenta de Google ya registrada no se puede registrar de nuevo (409)")
    void yaRegistrada() {
        when(usuarios.findByGoogleOauthSub("google-sub-1")).thenReturn(Optional.of(lucia()));

        assertThatThrownBy(() -> servicio.registrar(registro(null), http))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(authService, never()).crearCuenta(any(), any(), any());
    }

    @Test
    @DisplayName("Si Google no confirma el correo, no se puede registrar con Google (400)")
    void correoSinVerificar() {
        when(verificador.verificar("token-google"))
                .thenReturn(new DatosGoogle("google-sub-1", "lucia@gmail.com", false, "Lucía", null));

        assertThatThrownBy(() -> servicio.registrar(registro(null), http))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(authService, never()).crearCuenta(any(), any(), any());
    }

    @Test
    @DisplayName("Sin GOOGLE_CLIENT_ID configurado, el verificador responde 503")
    void sinClientId() {
        VerificadorGoogle sinConfigurar = new VerificadorGoogle(" ");

        assertThatThrownBy(() -> sinConfigurar.verificar("cualquier-token"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}

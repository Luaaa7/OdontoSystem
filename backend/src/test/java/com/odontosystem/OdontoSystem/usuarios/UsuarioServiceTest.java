package com.odontosystem.OdontoSystem.usuarios;

import com.odontosystem.OdontoSystem.entity.RolUsuario;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UsuarioServiceTest {

    @Mock private UsuarioRepository repositorio;

    private UsuarioService servicio;
    private Usuario lucia;

    @BeforeEach
    void setUp() {
        servicio = new UsuarioService(repositorio);
        lucia = Usuario.builder().id(UUID.randomUUID()).nombreCompleto("Lucía Ramos").correo("lucia@correo.pe")
                .telefono("987654321").numeroDocumento("70123456").rol(RolUsuario.PACIENTE).build();
        when(repositorio.findById(lucia.getId())).thenReturn(Optional.of(lucia));
    }

    @Test
    @DisplayName("Devuelve los datos de la cuenta, sin la contraseña")
    void obtener() {
        UsuarioService.UsuarioResponse r = servicio.obtener(lucia.getId());

        assertThat(r.correo()).isEqualTo("lucia@correo.pe");
        assertThat(r.tipoDocumento()).isEqualTo("DNI");
        assertThat(r.rol()).isEqualTo(RolUsuario.PACIENTE);
    }

    @Test
    @DisplayName("Una cuenta que no existe responde 404")
    void noExiste() {
        when(repositorio.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.obtener(UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("Actualiza el nombre (sin espacios sobrantes) y un teléfono libre")
    void actualiza() {
        UsuarioService.UsuarioResponse r = servicio.actualizar(lucia.getId(),
                new UsuarioService.ActualizarUsuarioRequest("  Lucía Ramos Pérez  ", "912345678"));

        assertThat(r.nombreCompleto()).isEqualTo("Lucía Ramos Pérez");
        assertThat(r.telefono()).isEqualTo("912345678");
        verify(repositorio).save(lucia);
    }

    @Test
    @DisplayName("Un teléfono que ya usa otra cuenta responde 409 y no guarda nada")
    void telefonoOcupado() {
        when(repositorio.existsByTelefono("912345678")).thenReturn(true);

        assertThatThrownBy(() -> servicio.actualizar(lucia.getId(),
                new UsuarioService.ActualizarUsuarioRequest(null, "912345678")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(repositorio, never()).save(any());
    }

    @Test
    @DisplayName("Si manda su mismo teléfono o un nombre vacío, no cambia nada ni consulta duplicados")
    void sinCambios() {
        servicio.actualizar(lucia.getId(), new UsuarioService.ActualizarUsuarioRequest("   ", "987654321"));

        assertThat(lucia.getNombreCompleto()).isEqualTo("Lucía Ramos");
        verify(repositorio, never()).existsByTelefono(anyString());
    }
}

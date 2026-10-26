package com.odontosystem.OdontoSystem.dto;

import com.odontosystem.OdontoSystem.entity.RolUsuario;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Entrar o registrarse con Google (RF16) en una sola llamada.
 * Solo id_token: entra si la cuenta existe; si es nueva responde perfil_completo = false.
 * Con id_token + los datos que Google no tiene (rol, documento, teléfono, COP si es odontólogo y
 * acepta_privacidad = true): crea la cuenta y entra.
 */
public record GoogleLoginRequest(
        @NotBlank(message = "El token de Google es obligatorio") String idToken,
        RolUsuario rol,
        @Pattern(regexp = "DNI|CE", message = "El tipo de documento debe ser DNI o CE") String tipoDocumento,
        @Size(max = 20) String numeroDocumento,
        @Size(max = 20) String telefono,
        @Size(max = 30) String numeroColegiatura,
        @Size(max = 150) String nombreCompleto,
        Boolean aceptaPrivacidad
) {
    /** true si trae datos de registro (el frontend ya mostró el formulario de cuenta nueva). */
    public boolean traeDatosDeRegistro() {
        return rol != null;
    }
}

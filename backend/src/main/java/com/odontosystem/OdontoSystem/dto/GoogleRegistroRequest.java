package com.odontosystem.OdontoSystem.dto;

import com.odontosystem.OdontoSystem.entity.RolUsuario;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registro con Google (RF16). El correo y el nombre salen del token de Google; aquí van los datos que Google no
 * tiene. nombre_completo es opcional: si no viene se usa el nombre de la cuenta de Google.
 */
public record GoogleRegistroRequest(
        @NotBlank(message = "El token de Google es obligatorio") String idToken,
        @NotBlank @Size(max = 20) String numeroDocumento,
        @NotBlank @Pattern(regexp = "DNI|CE", message = "El tipo de documento debe ser DNI o CE") String tipoDocumento,
        @NotBlank @Size(max = 20) String telefono,
        @NotNull RolUsuario rol,
        @Size(max = 30) String numeroColegiatura,
        @Size(max = 150) String nombreCompleto,
        @NotNull(message = "Debes aceptar la política de privacidad para registrarte")
        @AssertTrue(message = "Debes aceptar la política de privacidad para registrarte")
        Boolean aceptaPrivacidad
) {
}

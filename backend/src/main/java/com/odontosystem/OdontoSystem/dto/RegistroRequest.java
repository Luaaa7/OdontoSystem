package com.odontosystem.OdontoSystem.dto;

import com.odontosystem.OdontoSystem.entity.RolUsuario;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistroRequest(
        @NotBlank @Size(max = 20) String numeroDocumento,
        @NotBlank @Pattern(regexp = "DNI|CE", message = "El tipo de documento debe ser DNI o CE") String tipoDocumento,
        @NotBlank @Size(max = 150) String nombreCompleto,
        @NotBlank @Size(max = 20) String telefono,
        @NotBlank @Email @Size(max = 150) String correo,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotNull RolUsuario rol,
        @Size(max = 30) String numeroColegiatura, // obligatorio solo si rol=ODONTOLOGO (validado en AuthService)
        // RNF07 / Ley 29733: la casilla "Acepto la política de privacidad" debe venir marcada
        @NotNull(message = "Debes aceptar la política de privacidad para registrarte")
        @AssertTrue(message = "Debes aceptar la política de privacidad para registrarte")
        Boolean aceptaPrivacidad
) {}
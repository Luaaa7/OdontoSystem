package com.odontosystem.OdontoSystem.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Cambia la contraseña con el código de 6 dígitos que llegó por correo. */
public record RestablecerPasswordRequest(
        @NotBlank @Email @Size(max = 150) String correo,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "El código tiene 6 dígitos") String codigo,
        @NotBlank @Size(min = 8, max = 100) String passwordNueva
) {
}

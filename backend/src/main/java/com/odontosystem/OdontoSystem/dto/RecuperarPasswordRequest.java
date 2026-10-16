package com.odontosystem.OdontoSystem.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Pide un código para cambiar la contraseña olvidada. */
public record RecuperarPasswordRequest(
        @NotBlank @Email @Size(max = 150) String correo
) {
}

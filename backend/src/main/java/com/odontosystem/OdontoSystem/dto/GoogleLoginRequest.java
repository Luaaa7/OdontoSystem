package com.odontosystem.OdontoSystem.dto;

import jakarta.validation.constraints.NotBlank;

/** ID token que el frontend recibe de "Iniciar sesión con Google" (RF16). */
public record GoogleLoginRequest(
        @NotBlank(message = "El token de Google es obligatorio") String idToken
) {
}

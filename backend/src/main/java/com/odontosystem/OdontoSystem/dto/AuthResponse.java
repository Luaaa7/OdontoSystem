package com.odontosystem.OdontoSystem.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        String rol,
        String nombreCompleto,
        /* false: la cuenta aún no verificó su correo (OTP); el frontend debe pedir el código antes de reservar. */
        boolean correoVerificado
) {
    public AuthResponse(String accessToken, String refreshToken, String rol, String nombreCompleto) {
        this(accessToken, refreshToken, "Bearer", rol, nombreCompleto, false);
    }

    public AuthResponse(String accessToken, String refreshToken, String rol, String nombreCompleto,
                        boolean correoVerificado) {
        this(accessToken, refreshToken, "Bearer", rol, nombreCompleto, correoVerificado);
    }
}
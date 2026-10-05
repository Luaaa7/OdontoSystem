package com.odontosystem.OdontoSystem.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        String rol,
        String nombreCompleto
) {
    public AuthResponse(String accessToken, String refreshToken, String rol, String nombreCompleto) {
        this(accessToken, refreshToken, "Bearer", rol, nombreCompleto);
    }
}
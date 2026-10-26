package com.odontosystem.OdontoSystem.dto;

/**
 * Respuesta de POST /api/auth/google.
 * perfil_completo = true: trae los tokens (como el login normal).
 * perfil_completo = false: la cuenta de Google es nueva; el frontend pide rol, documento, teléfono (y COP)
 * y vuelve a llamar con esos datos. correo y nombre_completo vienen de Google para prellenar el formulario.
 */
public record GoogleAuthResponse(
        boolean perfilCompleto,
        String accessToken,
        String refreshToken,
        String tokenType,
        String rol,
        String nombreCompleto,
        String correo
) {
    public static GoogleAuthResponse sesion(AuthResponse a, String correo) {
        return new GoogleAuthResponse(true, a.accessToken(), a.refreshToken(), a.tokenType(), a.rol(), a.nombreCompleto(), correo);
    }

    public static GoogleAuthResponse faltanDatos(String correo, String nombreCompleto) {
        return new GoogleAuthResponse(false, null, null, null, null, nombreCompleto, correo);
    }
}

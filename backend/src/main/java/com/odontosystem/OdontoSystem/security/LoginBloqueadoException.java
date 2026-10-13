package com.odontosystem.OdontoSystem.security;

/** Demasiados intentos fallidos de inicio de sesión: se responde 429 con la cabecera Retry-After. */
public class LoginBloqueadoException extends RuntimeException {

    private final long segundosRestantes;

    public LoginBloqueadoException(long segundosRestantes) {
        super("Demasiados intentos fallidos. Vuelve a intentarlo en " + Math.max(1, (segundosRestantes + 59) / 60) + " minuto(s)");
        this.segundosRestantes = segundosRestantes;
    }

    public long getSegundosRestantes() {
        return segundosRestantes;
    }
}

package com.odontosystem.OdontoSystem.odontologos;

import java.text.Normalizer;
import java.util.Locale;

/** Comparación de textos sin tildes ni mayúsculas ("La Tinguiña" = "la tinguina"). */
final class Texto {

    private Texto() {
    }

    static String normalizar(String texto) {
        if (texto == null) return "";
        String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** null o solo espacios → null; en otro caso el texto sin espacios sobrantes. */
    static String limpiar(String texto) {
        if (texto == null) return null;
        String limpio = texto.trim();
        return limpio.isEmpty() ? null : limpio;
    }
}

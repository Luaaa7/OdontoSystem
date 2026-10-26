package com.odontosystem.OdontoSystem.chatbot;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reconoce distritos de la provincia de Ica dentro de un mensaje y los devuelve con su nombre oficial.
 *
 * "busco dentista en la tinguiña"  -> "La Tinguiña"
 * "Parcona"                        -> "Parcona"
 * "estoy por guadalupe"            -> "Salas"   (Guadalupe es la capital del distrito de Salas)
 *
 * Algunos nombres también son palabras o nombres comunes ("Santiago", "Salas", "Tate", "Ica").
 * Para no confundir "agendar con el Dr. Santiago Ruiz" con el distrito Santiago, esos solo
 * cuentan si van después de "en"/"de" o si el mensaje es únicamente el nombre del distrito.
 */
@Component
public class DistritoExtractor {

    /** Alias normalizado (sin tildes, minúsculas) -> nombre oficial para mostrar. */
    private static final Map<String, String> ALIAS = new LinkedHashMap<>();

    /** Alias que pueden ser otra cosa (un nombre de persona, una palabra común). */
    private static final Set<String> AMBIGUOS = Set.of("ica", "santiago", "salas", "tate", "yauca");

    static {
        alias("Ica Centro", "ica centro", "cercado de ica", "ica");
        alias("La Tinguiña", "la tinguina", "tinguina");
        alias("Los Aquijes", "los aquijes", "aquijes");
        alias("Ocucaje", "ocucaje");
        alias("Pachacútec", "pachacutec");
        alias("Parcona", "parcona");
        alias("Pueblo Nuevo", "pueblo nuevo");
        alias("Salas", "salas", "guadalupe");
        alias("San José de los Molinos", "san jose de los molinos", "los molinos");
        alias("San Juan Bautista", "san juan bautista");
        alias("Santiago", "santiago");
        alias("Subtanjalla", "subtanjalla");
        alias("Tate", "tate");
        alias("Yauca del Rosario", "yauca del rosario", "yauca");
    }

    private static void alias(String oficial, String... alias) {
        for (String a : alias) {
            ALIAS.put(a, oficial);
        }
    }

    /** Los alias más largos primero: "san juan bautista" antes que cualquier alias más corto. */
    private static final List<String> ALIAS_POR_LONGITUD = ALIAS.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    public Optional<String> extraer(String mensaje) {
        String texto = IntentDetector.normalizar(mensaje);
        if (texto.isEmpty()) {
            return Optional.empty();
        }
        String conBordes = " " + texto + " ";
        for (String alias : ALIAS_POR_LONGITUD) {
            if (!conBordes.contains(" " + alias + " ")) {
                continue;
            }
            boolean aceptado = !AMBIGUOS.contains(alias)
                    || texto.equals(alias)
                    || conBordes.contains(" en " + alias + " ")
                    || conBordes.contains(" de " + alias + " ");
            if (aceptado) {
                return Optional.of(ALIAS.get(alias));
            }
        }
        return Optional.empty();
    }

    /**
     * Compara el distrito que escribió un odontólogo en su perfil (texto libre) con un distrito oficial.
     * "la tinguiña", "La Tinguina" y "LA TINGUIÑA" valen igual que "La Tinguiña"; "Ica" vale "Ica Centro".
     */
    public boolean coincide(String distritoDelPerfil, String distritoOficial) {
        if (distritoDelPerfil == null || distritoOficial == null) {
            return false;
        }
        String oficialDelPerfil = extraer(distritoDelPerfil)
                .orElse(distritoDelPerfil);
        return IntentDetector.normalizar(oficialDelPerfil).equals(IntentDetector.normalizar(distritoOficial));
    }
}

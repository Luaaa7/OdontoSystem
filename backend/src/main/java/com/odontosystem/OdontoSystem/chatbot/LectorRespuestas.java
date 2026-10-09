package com.odontosystem.OdontoSystem.chatbot;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entiende las respuestas cortas que se dan en medio de una conversación:
 * un número de la lista ("2", "el segundo"), una fecha ("mañana", "el lunes", "12/10"),
 * una hora ("a las 10", "3 pm", "10:30") o un sí / no.
 * Todo es por reglas (sin IA), igual que el detector de intenciones.
 */
final class LectorRespuestas {

    private LectorRespuestas() {
    }

    private static final List<String> AFIRMACIONES = List.of(
            "si", "sip", "confirmo", "confirmar", "confirma", "dale", "ok", "okay", "claro", "de acuerdo",
            "correcto", "acepto", "listo", "perfecto", "reservar", "reserva", "si confirmo", "si por favor");

    private static final List<String> NEGACIONES = List.of(
            "no", "nop", "mejor no", "no gracias", "ninguno", "ninguna");

    private static final List<String> CANCELACIONES = List.of(
            "cancelar", "cancela", "cancelalo", "salir", "olvidalo", "menu", "volver al menu",
            "empezar de nuevo", "reiniciar", "detener", "ya no");

    private static final Map<String, Integer> ORDINALES = Map.ofEntries(
            Map.entry("primero", 1), Map.entry("primera", 1), Map.entry("primer", 1),
            Map.entry("segundo", 2), Map.entry("segunda", 2),
            Map.entry("tercero", 3), Map.entry("tercera", 3), Map.entry("tercer", 3),
            Map.entry("cuarto", 4), Map.entry("cuarta", 4),
            Map.entry("quinto", 5), Map.entry("quinta", 5),
            Map.entry("sexto", 6), Map.entry("sexta", 6));

    private static final Map<String, DayOfWeek> DIAS = Map.of(
            "lunes", DayOfWeek.MONDAY, "martes", DayOfWeek.TUESDAY, "miercoles", DayOfWeek.WEDNESDAY,
            "jueves", DayOfWeek.THURSDAY, "viernes", DayOfWeek.FRIDAY, "sabado", DayOfWeek.SATURDAY,
            "domingo", DayOfWeek.SUNDAY);

    private static final List<String> MESES = List.of("enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "setiembre", "octubre", "noviembre", "diciembre");

    private static final Pattern NUMERO_SOLO =
            Pattern.compile("^(?:el |la |opcion |numero |nro |n )?(\\d{1,2})$");
    private static final Pattern FECHA_NUMERICA =
            Pattern.compile("(?<![\\d:])(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?(?![\\d:])");
    private static final Pattern FECHA_CON_MES = Pattern.compile(
            "\\b(\\d{1,2}) de (enero|febrero|marzo|abril|mayo|junio|julio|agosto|setiembre|septiembre|octubre|noviembre|diciembre)\\b");
    private static final Pattern HORA_CON_MINUTOS =
            Pattern.compile("(?<![\\d/])(\\d{1,2})[:h](\\d{2})\\s*(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)?");
    private static final Pattern HORA_AM_PM =
            Pattern.compile("(?<![\\d/:])(\\d{1,2})\\s*(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)(?![a-z])");
    private static final Pattern HORA_A_LAS =
            Pattern.compile("\\ba las (\\d{1,2})(?![\\d/:])");

    /** "sí", "dale", "confirmo"… (el texto ya viene normalizado). */
    static boolean esAfirmacion(String normalizado) {
        return empiezaConAlguna(normalizado, AFIRMACIONES);
    }

    static boolean esNegacion(String normalizado) {
        return empiezaConAlguna(normalizado, NEGACIONES);
    }

    /** "cancelar", "menú", "empezar de nuevo": corta el flujo en curso. */
    static boolean esCancelacion(String normalizado) {
        return empiezaConAlguna(normalizado, CANCELACIONES);
    }

    private static boolean empiezaConAlguna(String texto, List<String> frases) {
        for (String frase : frases) {
            if (texto.equals(frase) || texto.startsWith(frase + " ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Posición (desde 0) elegida en una lista de {@code tamano} elementos:
     * "2", "el 2", "opción 2", "el segundo", "la última".
     */
    static Optional<Integer> indice(String normalizado, int tamano) {
        if (tamano <= 0) {
            return Optional.empty();
        }
        Matcher m = NUMERO_SOLO.matcher(normalizado);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(1));
            return n >= 1 && n <= tamano ? Optional.of(n - 1) : Optional.empty();
        }
        for (String palabra : normalizado.split(" ")) {
            Integer n = ORDINALES.get(palabra);
            if (n != null) {
                return n <= tamano ? Optional.of(n - 1) : Optional.empty();
            }
            if (palabra.equals("ultimo") || palabra.equals("ultima")) {
                return Optional.of(tamano - 1);
            }
        }
        return Optional.empty();
    }

    /** Número suelto ("10", "el 3"), sin am/pm ni minutos. */
    static Optional<Integer> numeroSuelto(String normalizado) {
        Matcher m = NUMERO_SOLO.matcher(normalizado);
        return m.matches() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    /**
     * Fecha mencionada en el mensaje, relativa a {@code hoy}:
     * "hoy", "mañana", "pasado mañana", "el lunes", "el próximo martes", "12/10", "12 de octubre".
     * "en la mañana" no cuenta como fecha.
     */
    static Optional<LocalDate> fecha(String original, LocalDate hoy) {
        String n = IntentDetector.normalizar(original);
        String conBordes = " " + n + " ";

        Matcher numerica = FECHA_NUMERICA.matcher(original == null ? "" : original);
        if (numerica.find()) {
            int dia = Integer.parseInt(numerica.group(1));
            int mes = Integer.parseInt(numerica.group(2));
            Integer anio = numerica.group(3) == null ? null : Integer.parseInt(numerica.group(3));
            Optional<LocalDate> f = construir(dia, mes, anio, hoy);
            if (f.isPresent()) return f;
        }
        Matcher conMes = FECHA_CON_MES.matcher(n);
        if (conMes.find()) {
            String nombreMes = conMes.group(2).equals("septiembre") ? "setiembre" : conMes.group(2);
            Optional<LocalDate> f = construir(Integer.parseInt(conMes.group(1)), MESES.indexOf(nombreMes) + 1, null, hoy);
            if (f.isPresent()) return f;
        }
        if (conBordes.contains(" pasado manana ")) {
            return Optional.of(hoy.plusDays(2));
        }
        if (conBordes.contains(" hoy ")) {
            return Optional.of(hoy);
        }
        if (conBordes.contains(" manana ") && !conBordes.matches(".* (la|esta|por la|en la|de la) manana .*")) {
            return Optional.of(hoy.plusDays(1));
        }
        for (Map.Entry<String, DayOfWeek> dia : DIAS.entrySet()) {
            if (conBordes.contains(" " + dia.getKey() + " ")) {
                boolean siguiente = conBordes.contains(" proximo " + dia.getKey() + " ")
                        || conBordes.contains(" siguiente " + dia.getKey() + " ");
                return Optional.of(siguiente
                        ? hoy.with(TemporalAdjusters.next(dia.getValue()))
                        : hoy.with(TemporalAdjusters.nextOrSame(dia.getValue())));
            }
        }
        return Optional.empty();
    }

    /** Sin año: la próxima vez que llegue esa fecha (si ya pasó este año, el siguiente). */
    private static Optional<LocalDate> construir(int dia, int mes, Integer anio, LocalDate hoy) {
        try {
            if (anio != null) {
                return Optional.of(LocalDate.of(anio < 100 ? 2000 + anio : anio, mes, dia));
            }
            LocalDate f = LocalDate.of(hoy.getYear(), mes, dia);
            return Optional.of(f.isBefore(hoy) ? f.plusYears(1) : f);
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Hora mencionada: "10:30", "10h30", "3 pm", "3:00 p.m.", "a las 4 de la tarde", "a las 9".
     * Un número suelto ("10") no se toma como hora aquí; eso lo decide quien llama según la lista.
     */
    static Optional<LocalTime> hora(String original) {
        if (original == null) {
            return Optional.empty();
        }
        String t = original.toLowerCase();
        String n = IntentDetector.normalizar(original);
        boolean tarde = n.contains("de la tarde") || n.contains("de la noche");

        Matcher m = HORA_CON_MINUTOS.matcher(t);
        if (m.find()) {
            return armar(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3), tarde);
        }
        m = HORA_AM_PM.matcher(t);
        if (m.find()) {
            return armar(Integer.parseInt(m.group(1)), 0, m.group(2), tarde);
        }
        m = HORA_A_LAS.matcher(n);
        if (m.find()) {
            return armar(Integer.parseInt(m.group(1)), 0, null, tarde);
        }
        return Optional.empty();
    }

    private static Optional<LocalTime> armar(int hora, int minutos, String amPm, boolean tarde) {
        boolean pm = tarde || (amPm != null && amPm.startsWith("p"));
        boolean am = amPm != null && amPm.startsWith("a");
        // Sin a.m./p.m., "a las 3" en un consultorio es de la tarde (de 1 a 6)
        if (amPm == null && !tarde && hora >= 1 && hora <= 6) pm = true;
        if (pm && hora < 12) hora += 12;
        if (am && hora == 12) hora = 0;
        if (hora > 23 || minutos > 59) {
            return Optional.empty();
        }
        return Optional.of(LocalTime.of(hora, minutos));
    }
}

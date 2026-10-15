package com.odontosystem.OdontoSystem.constancias;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generador mínimo de PDF de una página A4 (texto, rectángulos y líneas) sin librerías externas.
 * Usa las fuentes estándar Helvetica y Helvetica-Bold con codificación WinAnsi, así que acepta tildes y ñ.
 * Coordenadas en puntos desde la esquina inferior izquierda (A4 = 595 x 842).
 */
final class PdfSimple {

    static final float ANCHO = 595;
    static final float ALTO = 842;
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    private final ByteArrayOutputStream contenido = new ByteArrayOutputStream();

    /** Texto en (x, y). color en RGB de 0 a 1. */
    PdfSimple texto(String texto, float x, float y, float tamano, boolean negrita, float r, float g, float b) {
        escribir(String.format(Locale.ROOT, "BT %.3f %.3f %.3f rg /%s %.1f Tf %.2f %.2f Td (",
                r, g, b, negrita ? "F2" : "F1", tamano, x, y));
        contenido.writeBytes(escapar(texto));
        escribir(") Tj ET\n");
        return this;
    }

    PdfSimple texto(String texto, float x, float y, float tamano, boolean negrita) {
        return texto(texto, x, y, tamano, negrita, 0.12f, 0.16f, 0.2f);
    }

    /** Rectángulo relleno. */
    PdfSimple rectangulo(float x, float y, float ancho, float alto, float r, float g, float b) {
        escribir(String.format(Locale.ROOT, "%.3f %.3f %.3f rg %.2f %.2f %.2f %.2f re f\n", r, g, b, x, y, ancho, alto));
        return this;
    }

    PdfSimple linea(float x1, float y1, float x2, float y2) {
        escribir(String.format(Locale.ROOT, "0.8 0.84 0.87 RG 0.8 w %.2f %.2f m %.2f %.2f l S\n", x1, y1, x2, y2));
        return this;
    }

    /** Parte un texto en renglones de como máximo maxCaracteres (por palabras). */
    static List<String> partir(String texto, int maxCaracteres) {
        List<String> renglones = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String palabra : texto.split("\\s+")) {
            if (actual.length() > 0 && actual.length() + 1 + palabra.length() > maxCaracteres) {
                renglones.add(actual.toString());
                actual.setLength(0);
            }
            if (actual.length() > 0) actual.append(' ');
            actual.append(palabra);
        }
        if (actual.length() > 0) renglones.add(actual.toString());
        return renglones;
    }

    byte[] generar() {
        byte[] stream = contenido.toByteArray();
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        List<Integer> posiciones = new ArrayList<>();
        escribir(pdf, "%PDF-1.4\n%âãÏÓ\n");
        String[] objetos = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                        + "/Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> /Contents 4 0 R >>",
                null, // el contenido se escribe aparte (bytes)
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"
        };
        for (int i = 0; i < objetos.length; i++) {
            posiciones.add(pdf.size());
            escribir(pdf, (i + 1) + " 0 obj\n");
            if (objetos[i] == null) {
                escribir(pdf, "<< /Length " + stream.length + " >>\nstream\n");
                pdf.writeBytes(stream);
                escribir(pdf, "\nendstream");
            } else {
                escribir(pdf, objetos[i]);
            }
            escribir(pdf, "\nendobj\n");
        }
        int xref = pdf.size();
        escribir(pdf, "xref\n0 " + (objetos.length + 1) + "\n0000000000 65535 f \n");
        for (int p : posiciones) {
            escribir(pdf, String.format(Locale.ROOT, "%010d 00000 n \n", p));
        }
        escribir(pdf, "trailer\n<< /Size " + (objetos.length + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return pdf.toByteArray();
    }

    // ------------------------------------------------------------------

    /** Texto en WinAnsi, escapando \ ( ) y cambiando por ? lo que no exista en esa codificación. */
    static byte[] escapar(String texto) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        String limpio = texto == null ? "" : texto.replace('\n', ' ').replace('\r', ' ');
        for (int i = 0; i < limpio.length(); i++) {
            char c = limpio.charAt(i);
            if (c == '\\' || c == '(' || c == ')') {
                salida.write('\\');
                salida.write(c);
                continue;
            }
            String s = String.valueOf(c);
            if (!WIN_ANSI.newEncoder().canEncode(c)) s = "?";
            salida.writeBytes(s.getBytes(WIN_ANSI));
        }
        return salida.toByteArray();
    }

    private void escribir(String s) {
        contenido.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static void escribir(ByteArrayOutputStream salida, String s) {
        salida.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}

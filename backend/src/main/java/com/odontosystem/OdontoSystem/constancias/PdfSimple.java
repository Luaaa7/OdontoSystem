package com.odontosystem.OdontoSystem.constancias;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Generador de PDF de una página A4 (texto, rectángulos y líneas) sobre Apache PDFBox.
 * Incrusta la fuente Noto Sans (Unicode), por lo que conserva tildes, ñ y caracteres especiales.
 * Un carácter que la fuente no tenga se reemplaza por "?".
 * Coordenadas en puntos desde la esquina inferior izquierda (A4 = 595 x 842).
 */
final class PdfSimple {

    static final float ANCHO = 595;
    static final float ALTO = 842;
    private static final String FUENTE_NORMAL = "/fonts/NotoSans-Regular.ttf";
    private static final String FUENTE_NEGRITA = "/fonts/NotoSans-Bold.ttf";

    private final PDDocument documento = new PDDocument();
    private final PDPageContentStream contenido;
    private final PDFont fuenteNormal;
    private final PDFont fuenteNegrita;

    PdfSimple() {
        try {
            PDPage pagina = new PDPage(new PDRectangle(ANCHO, ALTO));
            documento.addPage(pagina);
            fuenteNormal = cargarFuente(FUENTE_NORMAL);
            fuenteNegrita = cargarFuente(FUENTE_NEGRITA);
            contenido = new PDPageContentStream(documento, pagina);
        } catch (IOException e) {
            cerrarDocumento();
            throw new UncheckedIOException("No se pudo preparar el PDF", e);
        }
    }

    /** Texto en (x, y). color en RGB de 0 a 1. */
    PdfSimple texto(String texto, float x, float y, float tamano, boolean negrita, float r, float g, float b) {
        PDFont fuente = negrita ? fuenteNegrita : fuenteNormal;
        try {
            contenido.beginText();
            contenido.setFont(fuente, tamano);
            contenido.setNonStrokingColor(r, g, b);
            contenido.newLineAtOffset(x, y);
            contenido.showText(limpiar(fuente, texto));
            contenido.endText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    PdfSimple texto(String texto, float x, float y, float tamano, boolean negrita) {
        return texto(texto, x, y, tamano, negrita, 0.12f, 0.16f, 0.2f);
    }

    /** Rectángulo relleno. */
    PdfSimple rectangulo(float x, float y, float ancho, float alto, float r, float g, float b) {
        try {
            contenido.setNonStrokingColor(r, g, b);
            contenido.addRect(x, y, ancho, alto);
            contenido.fill();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    PdfSimple linea(float x1, float y1, float x2, float y2) {
        try {
            contenido.setStrokingColor(0.8f, 0.84f, 0.87f);
            contenido.setLineWidth(0.8f);
            contenido.moveTo(x1, y1);
            contenido.lineTo(x2, y2);
            contenido.stroke();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        try {
            contenido.close();
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            documento.save(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            cerrarDocumento();
        }
    }

    // ------------------------------------------------------------------

    private PDFont cargarFuente(String ruta) throws IOException {
        try (InputStream in = PdfSimple.class.getResourceAsStream(ruta)) {
            if (in == null) {
                throw new IOException("No se encontró la fuente " + ruta);
            }
            return PDType0Font.load(documento, in);
        }
    }

    /** Cambia por "?" lo que la fuente no tenga y por espacio los saltos de línea y otros controles. */
    private static String limpiar(PDFont fuente, String texto) {
        if (texto == null) {
            return "";
        }
        StringBuilder limpio = new StringBuilder();
        texto.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) {
                limpio.append(' ');
                return;
            }
            String letra = new String(Character.toChars(cp));
            try {
                fuente.encode(letra);
                limpio.append(letra);
            } catch (IOException | IllegalArgumentException e) {
                limpio.append('?');
            }
        });
        return limpio.toString();
    }

    private void cerrarDocumento() {
        try {
            documento.close();
        } catch (IOException ignorado) {
            // ya se está terminando; no hay nada más que hacer
        }
    }
}
package com.odontosystem.OdontoSystem.constancias;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Diseño de la constancia de pago (RF14) en una página A4. No toca la BD: recibe los datos ya armados. */
final class ConstanciaPdf {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final Locale ES = Locale.of("es", "PE");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm", ES);
    private static final DateTimeFormatter FECHA_CITA = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy, HH:mm", ES);

    private ConstanciaPdf() {
    }

    /** Todo lo que se imprime; separado de la BD para poder probar el PDF solo. */
    record Datos(String codigo, String paciente, String documentoPaciente, String odontologo, String colegiatura,
                 String consultorio, String direccion, String servicio, OffsetDateTime fechaCita, BigDecimal precio,
                 BigDecimal deposito, String metodo, String numeroOperacion, OffsetDateTime pagadoEn,
                 OffsetDateTime devueltoEn, OffsetDateTime emitidoEn) {}

    static byte[] armar(Datos d) {
        PdfSimple pdf = new PdfSimple();
        float izq = 50;
        float der = PdfSimple.ANCHO - 50;
        // Encabezado
        pdf.rectangulo(0, PdfSimple.ALTO - 90, PdfSimple.ANCHO, 90, 0.059f, 0.463f, 0.431f);
        pdf.texto("OdontoSystem", izq, PdfSimple.ALTO - 45, 22, true, 1, 1, 1);
        pdf.texto("Citas odontológicas en Ica", izq, PdfSimple.ALTO - 66, 10, false, 0.85f, 0.96f, 0.94f);
        pdf.texto(d.codigo(), der - 120, PdfSimple.ALTO - 45, 12, true, 1, 1, 1);

        float y = PdfSimple.ALTO - 130;
        pdf.texto("CONSTANCIA DE PAGO DE GARANTÍA", izq, y, 16, true);
        y -= 18;
        pdf.texto("Documento no tributario: no es boleta ni factura.", izq, y, 10, false, 0.7f, 0.15f, 0.15f);
        y -= 14;
        pdf.texto("Emitida el " + FECHA_HORA.format(d.emitidoEn().atZoneSameInstant(LIMA)), izq, y, 9, false, 0.4f, 0.45f, 0.5f);

        y = seccion(pdf, "Paciente", y - 30, izq, der);
        y = fila(pdf, "Nombre", d.paciente(), y, izq);
        y = fila(pdf, "Documento", d.documentoPaciente(), y, izq);

        y = seccion(pdf, "Odontólogo", y - 12, izq, der);
        y = fila(pdf, "Nombre", d.odontologo(), y, izq);
        y = fila(pdf, "Colegiatura (COP)", d.colegiatura(), y, izq);
        y = fila(pdf, "Consultorio", d.consultorio(), y, izq);
        y = fila(pdf, "Dirección", d.direccion(), y, izq);

        y = seccion(pdf, "Cita", y - 12, izq, der);
        y = fila(pdf, "Servicio", d.servicio(), y, izq);
        y = fila(pdf, "Fecha", primeraMayuscula(FECHA_CITA.format(d.fechaCita().atZoneSameInstant(LIMA))), y, izq);

        y = seccion(pdf, "Pago del depósito", y - 12, izq, der);
        y = fila(pdf, "Precio del servicio", soles(d.precio()), y, izq);
        y = fila(pdf, "Depósito pagado", soles(d.deposito()), y, izq);
        BigDecimal saldo = d.precio() == null || d.deposito() == null ? null : d.precio().subtract(d.deposito());
        y = fila(pdf, "Saldo a pagar en el consultorio", d.devueltoEn() != null ? "-" : soles(saldo), y, izq);
        y = fila(pdf, "Medio de pago", d.metodo(), y, izq);
        y = fila(pdf, "N.º de operación", d.numeroOperacion(), y, izq);
        y = fila(pdf, "Pagado el", d.pagadoEn() == null ? "-" : FECHA_HORA.format(d.pagadoEn().atZoneSameInstant(LIMA)), y, izq);
        if (d.devueltoEn() != null) {
            y -= 6;
            pdf.rectangulo(izq, y - 8, der - izq, 24, 1f, 0.95f, 0.85f);
            pdf.texto("DEPÓSITO DEVUELTO AL PACIENTE el " + FECHA_HORA.format(d.devueltoEn().atZoneSameInstant(LIMA)),
                    izq + 10, y, 10, true, 0.6f, 0.35f, 0f);
            y -= 26;
        }

        y -= 20;
        pdf.linea(izq, y, der, y);
        y -= 18;
        List<String> nota = new ArrayList<>();
        nota.add("Esta constancia acredita que OdontoSystem recibió el depósito de garantía de la cita y lo mantiene en "
                + "custodia: se paga al odontólogo después de la atención o se devuelve al paciente según las reglas de "
                + "cancelación (100 % si se cancela con 12 horas o más de anticipación o si cancela el odontólogo).");
        nota.add("El comprobante de pago del servicio (boleta o factura electrónica) lo emite el odontólogo con su RUC "
                + "al momento de la atención, por el precio total del servicio.");
        for (String parrafo : nota) {
            for (String renglon : PdfSimple.partir(parrafo, 100)) {
                pdf.texto(renglon, izq, y, 8.5f, false, 0.32f, 0.38f, 0.43f);
                y -= 12;
            }
            y -= 6;
        }
        pdf.texto("Código de verificación: " + d.codigo(), izq, 40, 8, false, 0.5f, 0.55f, 0.6f);
        return pdf.generar();
    }

    // ------------------------------------------------------------------

    private static float seccion(PdfSimple pdf, String titulo, float y, float izq, float der) {
        pdf.texto(titulo.toUpperCase(ES), izq, y, 10, true, 0.059f, 0.463f, 0.431f);
        pdf.linea(izq, y - 5, der, y - 5);
        return y - 22;
    }

    private static float fila(PdfSimple pdf, String etiqueta, String valor, float y, float izq) {
        pdf.texto(etiqueta, izq, y, 10, false, 0.4f, 0.45f, 0.5f);
        pdf.texto(valor == null || valor.isBlank() ? "-" : valor, izq + 190, y, 10, true);
        return y - 17;
    }

    static String soles(BigDecimal monto) {
        return monto == null ? "-" : "S/ " + monto.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String primeraMayuscula(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(ES) + s.substring(1);
    }
}

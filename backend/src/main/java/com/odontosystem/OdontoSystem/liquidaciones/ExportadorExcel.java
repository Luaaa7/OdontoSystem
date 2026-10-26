package com.odontosystem.OdontoSystem.liquidaciones;

import com.odontosystem.OdontoSystem.liquidaciones.LiquidacionService.LiquidacionResponse;
import com.odontosystem.OdontoSystem.liquidaciones.LiquidacionService.MisIngresos;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Exporta "Mis ingresos" del odontólogo a un Excel (.xlsx) con Apache POI: un resumen (en custodia, pagado,
 * devuelto) y el detalle de cada depósito. Los montos van como números con formato de soles, para poder sumarlos.
 */
final class ExportadorExcel {

    static final String TIPO = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    static final String[] COLUMNAS = {"Fecha de la cita", "Paciente", "Servicio", "Estado de la cita", "Monto (S/)",
            "Estado del pago", "Retenido por", "Se paga desde", "N.º de operación", "Pagado el"};

    private ExportadorExcel() {
    }

    static byte[] exportar(MisIngresos ingresos) {
        try (Workbook libro = new XSSFWorkbook(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            Sheet hoja = libro.createSheet("Mis ingresos");
            CellStyle titulo = estiloTitulo(libro);
            CellStyle cabecera = estiloCabecera(libro);
            CellStyle soles = libro.createCellStyle();
            soles.setDataFormat(libro.createDataFormat().getFormat("\"S/\" #,##0.00"));

            int fila = 0;
            Row r = hoja.createRow(fila++);
            r.createCell(0).setCellValue("OdontoSystem · Mis ingresos por depósitos");
            r.getCell(0).setCellStyle(titulo);
            hoja.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));
            fila++;

            fila = resumen(hoja, fila, "En custodia", ingresos.enCustodia(), soles);
            fila = resumen(hoja, fila, "Pagado", ingresos.pagado(), soles);
            fila = resumen(hoja, fila, "Devuelto a pacientes", ingresos.devueltoAPacientes(), soles);
            fila++;

            Row encabezado = hoja.createRow(fila++);
            for (int i = 0; i < COLUMNAS.length; i++) {
                Cell c = encabezado.createCell(i);
                c.setCellValue(COLUMNAS[i]);
                c.setCellStyle(cabecera);
            }
            int primeraDeDetalle = fila;
            for (LiquidacionResponse l : ingresos.liquidaciones()) {
                Row d = hoja.createRow(fila++);
                d.createCell(0).setCellValue(fecha(l.fechaCita()));
                d.createCell(1).setCellValue(texto(l.paciente()));
                d.createCell(2).setCellValue(texto(l.servicio()));
                d.createCell(3).setCellValue(l.estadoCita() == null ? "" : l.estadoCita().name());
                Cell monto = d.createCell(4);
                monto.setCellValue(l.monto() == null ? 0 : l.monto().doubleValue());
                monto.setCellStyle(soles);
                d.createCell(5).setCellValue(l.estado() == null ? "" : l.estado().name());
                d.createCell(6).setCellValue(texto(l.retenidaPor()));
                d.createCell(7).setCellValue(fecha(l.pagarDesde()));
                d.createCell(8).setCellValue(texto(l.referenciaPago()));
                d.createCell(9).setCellValue(fecha(l.liquidadoEn()));
            }
            if (fila > primeraDeDetalle) {
                hoja.setAutoFilter(new CellRangeAddress(primeraDeDetalle - 1, fila - 1, 0, COLUMNAS.length - 1));
            }
            hoja.createFreezePane(0, primeraDeDetalle);
            for (int i = 0; i < COLUMNAS.length; i++) {
                hoja.setColumnWidth(i, i == 1 || i == 2 || i == 6 ? 28 * 256 : 18 * 256);
            }
            libro.write(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el Excel", e);
        }
    }

    private static int resumen(Sheet hoja, int fila, String etiqueta, BigDecimal monto, CellStyle soles) {
        Row r = hoja.createRow(fila);
        r.createCell(0).setCellValue(etiqueta);
        Cell c = r.createCell(1);
        c.setCellValue(monto == null ? 0 : monto.doubleValue());
        c.setCellStyle(soles);
        return fila + 1;
    }

    private static CellStyle estiloTitulo(Workbook libro) {
        Font fuente = libro.createFont();
        fuente.setBold(true);
        fuente.setFontHeightInPoints((short) 14);
        CellStyle estilo = libro.createCellStyle();
        estilo.setFont(fuente);
        return estilo;
    }

    private static CellStyle estiloCabecera(Workbook libro) {
        Font fuente = libro.createFont();
        fuente.setBold(true);
        fuente.setColor(IndexedColors.WHITE.getIndex());
        CellStyle estilo = libro.createCellStyle();
        estilo.setFont(fuente);
        estilo.setFillForegroundColor(IndexedColors.TEAL.getIndex());
        estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        estilo.setBorderBottom(BorderStyle.THIN);
        return estilo;
    }

    private static String fecha(OffsetDateTime f) {
        return f == null ? "" : FECHA.format(f.atZoneSameInstant(LIMA));
    }

    private static String texto(String t) {
        return t == null ? "" : t;
    }
}

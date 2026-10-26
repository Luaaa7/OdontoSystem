package com.odontosystem.OdontoSystem.liquidaciones;

import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoLiquidacion;
import com.odontosystem.OdontoSystem.liquidaciones.LiquidacionService.LiquidacionResponse;
import com.odontosystem.OdontoSystem.liquidaciones.LiquidacionService.MisIngresos;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExportadorExcelTest {

    private static Workbook leer(byte[] excel) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(excel));
    }

    private static Row filaCon(Sheet hoja, String primeraCelda) {
        for (Row r : hoja) {
            if (r.getCell(0) != null && primeraCelda.equals(r.getCell(0).getStringCellValue())) return r;
        }
        throw new AssertionError("No hay una fila con " + primeraCelda);
    }

    @Test
    @DisplayName("El Excel trae el resumen, la cabecera y una fila por depósito con el monto como número")
    void exporta() throws Exception {
        OffsetDateTime cita = OffsetDateTime.of(2026, 10, 20, 15, 0, 0, 0, ZoneOffset.UTC); // 10:00 en Lima
        var l = new LiquidacionResponse(UUID.randomUUID(), UUID.randomUUID(), "Lucía Ramos", "Resina estética", cita,
                EstadoCita.COMPLETADA, new BigDecimal("30.00"), EstadoLiquidacion.PENDIENTE, null,
                cita.plusHours(72), null, null);
        var ingresos = new MisIngresos(new BigDecimal("30.00"), new BigDecimal("48.50"), BigDecimal.ZERO, List.of(l));

        byte[] excel = ExportadorExcel.exportar(ingresos);

        try (Workbook libro = leer(excel)) {
            Sheet hoja = libro.getSheet("Mis ingresos");
            assertThat(hoja).isNotNull();
            assertThat(filaCon(hoja, "En custodia").getCell(1).getNumericCellValue()).isEqualTo(30.0);
            assertThat(filaCon(hoja, "Pagado").getCell(1).getNumericCellValue()).isEqualTo(48.5);
            Row cabecera = filaCon(hoja, "Fecha de la cita");
            assertThat(cabecera.getCell(4).getStringCellValue()).isEqualTo("Monto (S/)");
            Row detalle = hoja.getRow(cabecera.getRowNum() + 1);
            assertThat(detalle.getCell(0).getStringCellValue()).isEqualTo("20/10/2026 10:00");
            assertThat(detalle.getCell(1).getStringCellValue()).isEqualTo("Lucía Ramos");
            assertThat(detalle.getCell(4).getNumericCellValue()).isEqualTo(30.0);
            assertThat(detalle.getCell(5).getStringCellValue()).isEqualTo("PENDIENTE");
        }
    }

    @Test
    @DisplayName("Sin depósitos también genera un Excel válido (solo resumen y cabecera)")
    void vacio() throws Exception {
        byte[] excel = ExportadorExcel.exportar(new MisIngresos(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()));

        try (Workbook libro = leer(excel)) {
            assertThat(filaCon(libro.getSheet("Mis ingresos"), "Fecha de la cita")).isNotNull();
        }
    }
}

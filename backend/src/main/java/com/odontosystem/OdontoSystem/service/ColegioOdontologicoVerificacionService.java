package com.odontosystem.OdontoSystem.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class ColegioOdontologicoVerificacionService {

    private static final String URL_BUSCADOR = "https://siga.ccdp.org.pe/consultas_web/consulta_colegiado.asp";

    public ResultadoVerificacionCop verificar(String numeroColegiatura) {
        try {
            Document doc = Jsoup.connect(URL_BUSCADOR)
                    .data("TxtBusqueda", numeroColegiatura)
                    .timeout(10_000)
                    .userAgent("Mozilla/5.0 (compatible; OdontoSystemVerificador/1.0)")
                    .post();

            Elements filas = doc.select("table.lista tr");

            for (Element fila : filas) {
                Elements celdas = fila.select("td");
                if (celdas.size() < 4) {
                    continue; // fila de encabezado o fila oculta de paginación
                }

                String copEncontrado = celdas.get(0).text().trim();
                if (!copEncontrado.equals(numeroColegiatura.trim())) {
                    continue;
                }

                String nombre = celdas.get(2).text().trim();
                String region = celdas.get(3).text().trim();

                return new ResultadoVerificacionCop(EstadoVerificacionCop.VERIFICADO_IDENTIDAD, nombre, region);
            }

            return new ResultadoVerificacionCop(EstadoVerificacionCop.NO_ENCONTRADO, null, null);

        } catch (IOException e) {
            return new ResultadoVerificacionCop(EstadoVerificacionCop.ERROR_VERIFICACION, null, null);
        }
    }

    public enum EstadoVerificacionCop {
        VERIFICADO_IDENTIDAD,
        NO_ENCONTRADO,
        ERROR_VERIFICACION
    }

    public record ResultadoVerificacionCop(EstadoVerificacionCop estado, String nombreEncontrado, String region) {}
}
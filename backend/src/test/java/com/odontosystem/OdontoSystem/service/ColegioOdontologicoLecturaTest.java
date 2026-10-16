package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.service.ColegioOdontologicoVerificacionService.EstadoVerificacionCop;
import com.odontosystem.OdontoSystem.service.ColegioOdontologicoVerificacionService.ResultadoVerificacionCop;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Lectura de la tabla del buscador del COP con un HTML de ejemplo (sin internet). */
class ColegioOdontologicoLecturaTest {

    private static final String PAGINA = """
            <html><body>
              <table class="lista">
                <tr><th>COP</th><th>Estado</th><th>Nombre</th><th>Región</th></tr>
                <tr><td>01000</td><td>HABILITADO</td><td>PEREZ ROJAS ANA MARIA</td><td>ICA</td></tr>
                <tr><td>12345</td><td>HABILITADO</td><td>QUISPE LUIS</td><td>LIMA</td></tr>
                <tr style="display:none"><td colspan="4">Página 1 de 1</td></tr>
              </table>
            </body></html>
            """;

    private final Document doc = Jsoup.parse(PAGINA);

    @Test
    @DisplayName("Encuentra al odontólogo aunque el COP aparezca con ceros a la izquierda y devuelve su nombre y región")
    void encontrado() {
        ResultadoVerificacionCop r = ColegioOdontologicoVerificacionService.leerResultado(doc, "1000");

        assertThat(r.estado()).isEqualTo(EstadoVerificacionCop.VERIFICADO_IDENTIDAD);
        assertThat(r.nombreEncontrado()).isEqualTo("PEREZ ROJAS ANA MARIA");
        assertThat(r.region()).isEqualTo("ICA");
    }

    @Test
    @DisplayName("Si el número no está en la tabla, el resultado es NO_ENCONTRADO")
    void noEncontrado() {
        ResultadoVerificacionCop r = ColegioOdontologicoVerificacionService.leerResultado(doc, "99999");

        assertThat(r.estado()).isEqualTo(EstadoVerificacionCop.NO_ENCONTRADO);
        assertThat(r.nombreEncontrado()).isNull();
    }

    @Test
    @DisplayName("Una página sin tabla de resultados también es NO_ENCONTRADO (no revienta)")
    void sinTabla() {
        ResultadoVerificacionCop r = ColegioOdontologicoVerificacionService.leerResultado(
                Jsoup.parse("<html><body><p>No se encontraron registros</p></body></html>"), "1000");

        assertThat(r.estado()).isEqualTo(EstadoVerificacionCop.NO_ENCONTRADO);
    }
}

package com.odontosystem.OdontoSystem.usuarios;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentimientoServiceTest {

    @Test
    @DisplayName("Solo se guarda una IP con forma válida (IPv4 o IPv6); cualquier otra cosa queda en null")
    void ipValida() {
        assertThat(ConsentimientoService.ipValida("190.12.3.4")).isEqualTo("190.12.3.4");
        assertThat(ConsentimientoService.ipValida("0:0:0:0:0:0:0:1")).isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(ConsentimientoService.ipValida(" 10.0.0.1 ")).isEqualTo("10.0.0.1");
        assertThat(ConsentimientoService.ipValida("1.2.3.4'; drop table x")).isNull();
        assertThat(ConsentimientoService.ipValida(null)).isNull();
    }

    @Test
    @DisplayName("La versión vigente sale de la configuración")
    void version() {
        assertThat(new ConsentimientoService(null, "2026-10-v1").versionVigente()).isEqualTo("2026-10-v1");
    }
}

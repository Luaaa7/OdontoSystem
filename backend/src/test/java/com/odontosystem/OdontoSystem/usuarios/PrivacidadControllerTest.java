package com.odontosystem.OdontoSystem.usuarios;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivacidadControllerTest {

    @Test
    @DisplayName("La página de privacidad muestra la versión vigente y el correo de contacto")
    void pagina() {
        ConsentimientoService consentimientos = mock(ConsentimientoService.class);
        when(consentimientos.versionVigente()).thenReturn("2026-10-v1");
        var modelo = new ExtendedModelMap();

        String vista = new PrivacidadController(consentimientos, "contacto@odontosystem.test").privacidad(modelo);

        assertThat(vista).isEqualTo("paginas/privacidad");
        assertThat(modelo.getAttribute("version")).isEqualTo("2026-10-v1");
        assertThat(modelo.getAttribute("contacto")).isEqualTo("contacto@odontosystem.test");
    }

    @Test
    @DisplayName("Sin correo configurado no muestra contacto")
    void sinContacto() {
        var modelo = new ExtendedModelMap();

        new PrivacidadController(mock(ConsentimientoService.class), "").privacidad(modelo);

        assertThat(modelo.getAttribute("contacto")).isNull();
    }
}

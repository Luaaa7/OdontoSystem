package com.odontosystem.OdontoSystem.usuarios;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Página pública con la política de privacidad (Ley N.º 29733). Es la que el usuario acepta al registrarse
 * (versión en app.privacidad.version) y la URL que se declara en Google Play.
 */
@Hidden
@Controller
public class PrivacidadController {

    private final ConsentimientoService consentimientos;
    private final String contacto;

    public PrivacidadController(ConsentimientoService consentimientos,
                                @Value("${app.correo.remitente:}") String contacto) {
        this.consentimientos = consentimientos;
        this.contacto = contacto;
    }

    @GetMapping("/privacidad")
    public String privacidad(Model modelo) {
        modelo.addAttribute("version", consentimientos.versionVigente());
        modelo.addAttribute("contacto", contacto == null || contacto.isBlank() ? null : contacto);
        return "paginas/privacidad";
    }
}

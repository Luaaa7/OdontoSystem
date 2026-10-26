package com.odontosystem.OdontoSystem.notificaciones;

import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;
import java.util.Map;

/** Arma el HTML de los correos con las plantillas de src/main/resources/templates/correo. */
@Component
public class PlantillasCorreo {

    private static final Locale ES = Locale.of("es", "PE");
    private final ITemplateEngine motor;

    public PlantillasCorreo(ITemplateEngine motor) {
        this.motor = motor;
    }

    public String armar(String plantilla, Map<String, Object> variables) {
        Context contexto = new Context(ES);
        contexto.setVariables(variables);
        return motor.process("correo/" + plantilla, contexto);
    }
}

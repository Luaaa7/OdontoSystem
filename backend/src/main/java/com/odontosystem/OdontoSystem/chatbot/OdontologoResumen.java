package com.odontosystem.OdontoSystem.chatbot;

import com.odontosystem.OdontoSystem.odontologos.OdontologoDtos.OdontologoTarjeta;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Tarjeta de un odontólogo dentro de una respuesta "lista_odontologos" del chatbot. */
public record OdontologoResumen(
        UUID id,
        String nombre,
        String fotoUrl,
        String consultorio,
        String distrito,
        BigDecimal calificacion,
        Integer totalResenas,
        List<String> especialidades,
        BigDecimal precioDesde
) {
    static OdontologoResumen desde(OdontologoTarjeta t) {
        return new OdontologoResumen(t.id(), t.nombre(), t.fotoUrl(), t.consultorio(), t.distrito(),
                t.calificacion(), t.totalResenas(), t.especialidades(), t.precioDesde());
    }
}

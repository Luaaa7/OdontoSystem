package com.odontosystem.OdontoSystem.chatbot;

import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;

import java.math.BigDecimal;
import java.util.UUID;

/** Tarjeta de un odontólogo dentro de una respuesta "lista_odontologos" del chatbot. */
public record OdontologoResumen(
        UUID id,
        String nombre,
        String consultorio,
        String distrito,
        BigDecimal calificacion,
        Integer totalResenas
) {
    static OdontologoResumen desde(PerfilOdontologo perfil) {
        return new OdontologoResumen(
                perfil.getUsuarioId(),
                perfil.getUsuario().getNombreCompleto(),
                perfil.getNombreConsultorio(),
                perfil.getDistritoConsultorio(),
                perfil.getCalificacionPromedio(),
                perfil.getTotalResenas()
        );
    }
}

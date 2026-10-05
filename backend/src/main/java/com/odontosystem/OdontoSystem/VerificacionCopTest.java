package com.odontosystem.OdontoSystem;

import com.odontosystem.OdontoSystem.service.ColegioOdontologicoVerificacionService;

public class VerificacionCopTest {
    public static void main(String[] args) {
        ColegioOdontologicoVerificacionService servicio = new ColegioOdontologicoVerificacionService();

        var resultado = servicio.verificar("07462"); // pon aquí tu propio número de COP

        System.out.println("Estado: " + resultado.estado());
        System.out.println("Nombre encontrado: " + resultado.nombreEncontrado());
        System.out.println("Región: " + resultado.region());
    }
}
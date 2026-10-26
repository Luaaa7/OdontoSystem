package com.odontosystem.OdontoSystem.reclamos;

import java.util.UUID;

/** Se publica al registrar o responder un reclamo; el aviso por correo sale después del commit. */
public record ReclamoRegistrado(UUID reclamoId, Aviso aviso) {

    public enum Aviso { RECIBIDO, RESPONDIDO }
}

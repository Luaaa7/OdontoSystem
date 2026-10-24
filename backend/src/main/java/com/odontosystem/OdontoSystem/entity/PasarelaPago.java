package com.odontosystem.OdontoSystem.entity;

/**
 * Procesador técnico del pago (tipo pasarela_pago).
 * MERCADO_PAGO es la pasarela real y SIMULADA la de la demo. CULQI y NIUBIZ ya no se usan: quedan porque
 * existen en la BD y algunos pagos de prueba antiguos los tienen.
 */
public enum PasarelaPago {
    CULQI, NIUBIZ, MERCADO_PAGO, SIMULADA
}


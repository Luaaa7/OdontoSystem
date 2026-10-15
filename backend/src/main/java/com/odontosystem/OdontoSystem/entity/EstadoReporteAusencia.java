package com.odontosystem.OdontoSystem.entity;

/** Estado de un reporte "el odontólogo no llegó" (columna reportes_ausencia.estado). */
public enum EstadoReporteAusencia {
    /** Disputa abierta: el odontólogo tiene 48 h para responder con prueba. */
    PENDIENTE,
    /** El odontólogo faltó (lo admitió o no respondió): reembolso al paciente y falta al odontólogo. */
    CONFIRMADO,
    /** El odontólogo probó que atendió (historia clínica): strike al paciente por reporte falso. */
    RECHAZADO,
    /** Sin prueba clara o paciente con reportes repetidos: sigue como reclamo. */
    EN_RECLAMO
}

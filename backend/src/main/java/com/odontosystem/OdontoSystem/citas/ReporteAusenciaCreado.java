package com.odontosystem.OdontoSystem.citas;

import java.util.UUID;

/** Un paciente reportó que el odontólogo no llegó: se le avisa al odontólogo después del commit. */
public record ReporteAusenciaCreado(UUID reporteId) {}

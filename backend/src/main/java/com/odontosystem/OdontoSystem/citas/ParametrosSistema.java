package com.odontosystem.OdontoSystem.citas;

import com.odontosystem.OdontoSystem.entity.ConfiguracionSistema;
import com.odontosystem.OdontoSystem.repository.ConfiguracionSistemaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Lee los parámetros de configuracion_sistema; si falta o no es número, usa el valor por defecto. */
@Component
@RequiredArgsConstructor
public class ParametrosSistema {

    public static final String HORAS_CANCELACION_REEMBOLSO = "HORAS_MINIMAS_CANCELACION_REEMBOLSO";
    public static final String LIMITE_STRIKES = "LIMITE_MAXIMO_STRIKES";
    /** Cancelar con menos de estas horas suma un strike (no está en la BD: usa 2 por defecto). */
    public static final String HORAS_CANCELACION_STRIKE = "HORAS_CANCELACION_CON_STRIKE";

    private final ConfiguracionSistemaRepository repository;

    public int entero(String clave, int porDefecto) {
        return repository.findById(clave)
                .map(ConfiguracionSistema::getValor)
                .map(String::trim)
                .flatMap(v -> {
                    try {
                        return java.util.Optional.of(Integer.parseInt(v));
                    } catch (NumberFormatException e) {
                        return java.util.Optional.empty();
                    }
                })
                .orElse(porDefecto);
    }
}

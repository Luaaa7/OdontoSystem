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

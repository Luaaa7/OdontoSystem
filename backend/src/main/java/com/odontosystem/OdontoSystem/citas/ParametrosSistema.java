package com.odontosystem.OdontoSystem.citas;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.primitives.Ints;
import com.odontosystem.OdontoSystem.entity.ConfiguracionSistema;
import com.odontosystem.OdontoSystem.repository.ConfiguracionSistemaRepository;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Lee los parámetros de configuracion_sistema; si falta o no es número, usa el valor por defecto.
 *
 * <p>Los valores se guardan en memoria con un caché de Google Guava ({@link LoadingCache}) durante
 * {@link #VIGENCIA}: las reglas de citas, strikes y reembolsos consultan estos parámetros en cada operación
 * y así no se hace una consulta a la BD cada vez. Un cambio en la tabla se aplica, como máximo, al vencer el caché.
 * {@link Ints#tryParse(String)} convierte el texto sin lanzar excepciones.</p>
 */
@Component
public class ParametrosSistema {

    public static final String HORAS_CANCELACION_REEMBOLSO = "HORAS_MINIMAS_CANCELACION_REEMBOLSO";
    public static final String LIMITE_STRIKES = "LIMITE_MAXIMO_STRIKES";
    /** Cancelar con menos de estas horas suma un strike (no está en la BD: usa 2 por defecto). */
    public static final String HORAS_CANCELACION_STRIKE = "HORAS_CANCELACION_CON_STRIKE";

    /** Tiempo que un valor leído de la BD se reutiliza antes de volver a consultarlo. */
    static final Duration VIGENCIA = Duration.ofMinutes(5);

    private final ConfiguracionSistemaRepository repository;

    /** Clave del parámetro → valor numérico (vacío si no existe o no es número). Guava no admite null: por eso Optional. */
    private final LoadingCache<String, Optional<Integer>> cache;

    public ParametrosSistema(ConfiguracionSistemaRepository repository) {
        this.repository = repository;
        this.cache = CacheBuilder.newBuilder()
                .expireAfterWrite(VIGENCIA)
                .maximumSize(100)
                .build(CacheLoader.from(this::leer));
    }

    public int entero(String clave, int porDefecto) {
        return cache.getUnchecked(clave).orElse(porDefecto);
    }

    /** Descarta los valores en memoria (por ejemplo, después de cambiar la tabla desde una migración o una prueba). */
    public void limpiarCache() {
        cache.invalidateAll();
    }

    private Optional<Integer> leer(String clave) {
        return repository.findById(clave)
                .map(ConfiguracionSistema::getValor)
                .map(String::trim)
                .map(Ints::tryParse);
    }
}

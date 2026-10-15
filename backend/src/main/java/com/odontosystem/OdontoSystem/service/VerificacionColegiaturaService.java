package com.odontosystem.OdontoSystem.service;

import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.ResultadoVerificacionCop;
import com.odontosystem.OdontoSystem.entity.VerificacionCop;
import com.odontosystem.OdontoSystem.repository.PerfilOdontologoRepository;
import com.odontosystem.OdontoSystem.repository.VerificacionCopRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RF09: verificación de la colegiatura contra el buscador público del COP.
 * <ul>
 *   <li>Encontrado y el nombre coincide → VERIFICADO (ya puede recibir reservas).</li>
 *   <li>Encontrado pero el nombre no coincide → OBSERVADO; no encontrado → RECHAZADO.</li>
 *   <li>La página del COP no respondió → sigue PENDIENTE y se <b>reintenta solo</b> con espera creciente
 *       (1 h, 2 h, 4 h, 8 h) hasta 5 intentos. El odontólogo también puede pedir un reintento desde su panel.</li>
 * </ul>
 * Cada intento queda registrado en verificaciones_cop.
 */
@Service
public class VerificacionColegiaturaService {

    static final int MAX_INTENTOS_AUTOMATICOS = 5;
    static final Duration ESPERA_BASE = Duration.ofHours(1);
    static final Duration ESPERA_MANUAL = Duration.ofMinutes(10);

    public record EstadoVerificacionResponse(EstadoVerificacionOdontologo estado, String nota, long intentosFallidos,
                                             OffsetDateTime ultimoIntento) {}

    private final PerfilOdontologoRepository perfilRepository;
    private final VerificacionCopRepository verificacionRepository;
    private final ColegioOdontologicoVerificacionService colegio;
    private final Clock clock;

    public VerificacionColegiaturaService(PerfilOdontologoRepository perfilRepository,
                                          VerificacionCopRepository verificacionRepository,
                                          ColegioOdontologicoVerificacionService colegio, Clock clock) {
        this.perfilRepository = perfilRepository;
        this.verificacionRepository = verificacionRepository;
        this.colegio = colegio;
        this.clock = clock;
    }

    /** Consulta el COP, actualiza el estado del perfil, lo guarda y registra el intento. */
    @Transactional
    public PerfilOdontologo verificarYGuardar(PerfilOdontologo perfil) {
        String numero = perfil.getNumeroColegiatura();
        String nombreUsuario = perfil.getUsuario().getNombreCompleto();
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        var resultado = colegio.verificar(numero);

        ResultadoVerificacionCop resultadoParaLog;
        String detalle;
        switch (resultado.estado()) {
            case VERIFICADO_IDENTIDAD -> {
                if (nombresCoinciden(nombreUsuario, resultado.nombreEncontrado())) {
                    perfil.setColegiaturaVerificada(true);
                    perfil.setColegiaturaVerificadaEn(ahora);
                    perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.VERIFICADO);
                    perfil.setVerificadoEn(ahora);
                    perfil.setNotaObservacion(null);
                    resultadoParaLog = ResultadoVerificacionCop.VERIFICADO;
                    detalle = "Identidad confirmada contra el registro nacional COP: " + resultado.nombreEncontrado()
                            + " (" + resultado.region() + ").";
                } else {
                    perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.OBSERVADO);
                    perfil.setNotaObservacion("El nombre registrado no coincide claramente con el nombre encontrado en el COP ("
                            + resultado.nombreEncontrado() + "). Requiere revisión.");
                    resultadoParaLog = ResultadoVerificacionCop.RECHAZADO;
                    detalle = "COP encontrado pero el nombre no coincide: esperado='" + nombreUsuario
                            + "', encontrado='" + resultado.nombreEncontrado() + "'.";
                }
            }
            case NO_ENCONTRADO -> {
                perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.RECHAZADO);
                perfil.setNotaObservacion("No se encontró el número de colegiatura " + numero + " en el registro nacional del COP.");
                resultadoParaLog = ResultadoVerificacionCop.RECHAZADO;
                detalle = "Número de COP no encontrado en el buscador nacional.";
            }
            default -> {
                long fallidos = perfil.getUsuarioId() == null ? 0
                        : verificacionRepository.countByOdontologo_UsuarioIdAndResultado(perfil.getUsuarioId(),
                        ResultadoVerificacionCop.ERROR);
                perfil.setEstadoVerificacion(EstadoVerificacionOdontologo.PENDIENTE);
                perfil.setNotaObservacion(fallidos + 1 >= MAX_INTENTOS_AUTOMATICOS
                        ? "No se pudo consultar el COP después de varios intentos. Pulsa \"Reintentar verificación\" más tarde."
                        : "No se pudo verificar automáticamente en este momento; se reintentará.");
                resultadoParaLog = ResultadoVerificacionCop.ERROR;
                detalle = "Error de red/parsing al consultar el buscador nacional del COP.";
            }
        }

        PerfilOdontologo guardado = perfilRepository.save(perfil);
        verificacionRepository.save(VerificacionCop.builder()
                .odontologo(guardado)
                .numeroCop(numero)
                .resultado(resultadoParaLog)
                .detalle(detalle)
                .ejecutadoEn(ahora)
                .build());
        return guardado;
    }

    /** Tarea programada: reintenta los perfiles PENDIENTE cuya espera ya pasó (1 h, 2 h, 4 h, 8 h). */
    @Transactional
    public int reintentarPendientes() {
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        int reintentados = 0;
        for (PerfilOdontologo perfil : perfilRepository.findByEstadoVerificacion(EstadoVerificacionOdontologo.PENDIENTE)) {
            if (perfil.getDesactivadoEn() != null) continue;
            UUID id = perfil.getUsuarioId();
            long fallidos = verificacionRepository.countByOdontologo_UsuarioIdAndResultado(id, ResultadoVerificacionCop.ERROR);
            if (fallidos >= MAX_INTENTOS_AUTOMATICOS) continue;
            OffsetDateTime ultimo = verificacionRepository.findFirstByOdontologo_UsuarioIdOrderByEjecutadoEnDesc(id)
                    .map(VerificacionCop::getEjecutadoEn).orElse(null);
            if (ultimo != null && ahora.isBefore(ultimo.plus(espera(fallidos)))) continue;
            verificarYGuardar(perfil);
            reintentados++;
        }
        return reintentados;
    }

    /** El odontólogo pide un reintento desde su panel (como máximo uno cada 10 minutos). */
    @Transactional
    public EstadoVerificacionResponse reintentarAhora(UUID odontologoId) {
        PerfilOdontologo perfil = perfilRepository.findById(odontologoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Perfil de odontólogo no encontrado"));
        if (perfil.getEstadoVerificacion() != EstadoVerificacionOdontologo.PENDIENTE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tu colegiatura ya fue revisada (" + perfil.getEstadoVerificacion() + "); no hace falta reintentar");
        }
        OffsetDateTime ultimo = verificacionRepository.findFirstByOdontologo_UsuarioIdOrderByEjecutadoEnDesc(odontologoId)
                .map(VerificacionCop::getEjecutadoEn).orElse(null);
        if (ultimo != null && OffsetDateTime.now(clock).isBefore(ultimo.plus(ESPERA_MANUAL))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Espera unos minutos antes de volver a intentar la verificación");
        }
        verificarYGuardar(perfil);
        return estado(odontologoId);
    }

    @Transactional(readOnly = true)
    public EstadoVerificacionResponse estado(UUID odontologoId) {
        PerfilOdontologo perfil = perfilRepository.findById(odontologoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Perfil de odontólogo no encontrado"));
        return new EstadoVerificacionResponse(perfil.getEstadoVerificacion(), perfil.getNotaObservacion(),
                verificacionRepository.countByOdontologo_UsuarioIdAndResultado(odontologoId, ResultadoVerificacionCop.ERROR),
                verificacionRepository.findFirstByOdontologo_UsuarioIdOrderByEjecutadoEnDesc(odontologoId)
                        .map(VerificacionCop::getEjecutadoEn).orElse(null));
    }

    // ------------------------------------------------------------------

    /** 1 h después del primer fallo, 2 h después del segundo, 4 h, 8 h... */
    static Duration espera(long fallidos) {
        return ESPERA_BASE.multipliedBy(1L << Math.max(0, Math.min(fallidos - 1, 10)));
    }

    /** Compara nombres tolerando orden distinto, acentos y mayúsculas/minúsculas. */
    static boolean nombresCoinciden(String nombreA, String nombreB) {
        if (nombreA == null || nombreB == null) return false;
        Set<String> tokensA = normalizarYTokenizar(nombreA);
        Set<String> tokensB = normalizarYTokenizar(nombreB);
        long coincidencias = tokensA.stream().filter(tokensB::contains).count();
        int minimo = Math.min(tokensA.size(), tokensB.size());
        return coincidencias >= Math.max(1, minimo - 1); // tolera 1 token de diferencia
    }

    private static Set<String> normalizarYTokenizar(String texto) {
        String sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase();
        return Arrays.stream(sinAcentos.split("\\s+"))
                .filter(t -> !t.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
    }
}

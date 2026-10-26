package com.odontosystem.OdontoSystem.notificaciones;

import com.odontosystem.OdontoSystem.entity.DispositivoUsuario;
import com.odontosystem.OdontoSystem.repository.DispositivoUsuarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Notificaciones push (RF10, app móvil): registro de los celulares de cada usuario y envío.
 * Sale una push con cada correo de aviso (cita confirmada, pago recibido, cancelación) y con cada recordatorio.
 * Si FCM dice que un token ya no existe (app desinstalada), se borra.
 */
@Service
public class PushService {

    static final int MAX_DISPOSITIVOS = 10;
    private static final Set<String> SISTEMAS = Set.of("ANDROID", "IOS", "WEB");

    public record DispositivoRequest(@jakarta.validation.constraints.NotBlank
                                     @jakarta.validation.constraints.Size(max = 4096) String token,
                                     @jakarta.validation.constraints.Size(max = 20) String sistemaOperativo) {}

    private final DispositivoUsuarioRepository dispositivos;
    private final EnviadorPush enviador;
    private final Clock clock;

    public PushService(DispositivoUsuarioRepository dispositivos, EnviadorPush enviador, Clock clock) {
        this.dispositivos = dispositivos;
        this.enviador = enviador;
        this.clock = clock;
    }

    public boolean activo() {
        return enviador.activo();
    }

    /** El celular avisa su token (al iniciar sesión o cuando Firebase lo renueva). Si ya estaba, solo se actualiza. */
    @Transactional
    public void registrar(UUID usuarioId, DispositivoRequest req) {
        String token = req.token().strip();
        String sistema = req.sistemaOperativo() == null ? null : req.sistemaOperativo().strip().toUpperCase();
        if (sistema != null && !SISTEMAS.contains(sistema)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sistema_operativo debe ser ANDROID, IOS o WEB");
        }
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        DispositivoUsuario d = dispositivos.findByUsuarioIdAndTokenFcm(usuarioId, token).orElse(null);
        if (d == null) {
            List<DispositivoUsuario> actuales = dispositivos.findByUsuarioId(usuarioId);
            if (actuales.size() >= MAX_DISPOSITIVOS) {
                // se olvida el que lleva más tiempo sin usarse
                actuales.stream().min((a, b) -> a.getUltimaActividadEn().compareTo(b.getUltimaActividadEn()))
                        .ifPresent(dispositivos::delete);
            }
            d = DispositivoUsuario.builder().usuarioId(usuarioId).tokenFcm(token).build();
        }
        d.setSistemaOperativo(sistema);
        d.setUltimaActividadEn(ahora);
        dispositivos.save(d);
    }

    /** Al cerrar sesión en ese celular: deja de recibir push. */
    @Transactional
    public void quitar(UUID usuarioId, String token) {
        dispositivos.borrarDeUsuario(usuarioId, token.strip());
    }

    /** Manda la push a todos los celulares del usuario. Devuelve cuántos la recibieron. Nunca lanza. */
    @Transactional
    public int enviarAUsuario(UUID usuarioId, String titulo, String cuerpo, Map<String, String> datos) {
        if (!enviador.activo() || usuarioId == null) {
            return 0;
        }
        int enviadas = 0;
        for (DispositivoUsuario d : dispositivos.findByUsuarioId(usuarioId)) {
            switch (enviador.enviar(d.getTokenFcm(), titulo, cuerpo, datos)) {
                case ENVIADO -> enviadas++;
                case INVALIDO -> dispositivos.borrarToken(d.getTokenFcm());
                case FALLIDO -> { }
            }
        }
        return enviadas;
    }
}

package com.odontosystem.OdontoSystem.notificaciones;

import com.odontosystem.OdontoSystem.citas.CitaCambioEstado;
import com.odontosystem.OdontoSystem.entity.CanalNotificacion;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoNotificacion;
import com.odontosystem.OdontoSystem.entity.RecordatorioCita;
import com.odontosystem.OdontoSystem.entity.RegistroNotificacion;
import com.odontosystem.OdontoSystem.entity.TipoNotificacion;
import com.odontosystem.OdontoSystem.repository.CitaRepository;
import com.odontosystem.OdontoSystem.repository.RecordatorioCitaRepository;
import com.odontosystem.OdontoSystem.repository.RegistroNotificacionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * RF10: recordatorios de cita y avisos por correo.
 *
 * <b>Recordatorios.</b> Cada cita CONFIRMADA recibe dos: 24 horas y 2 horas antes (solo los que todavía
 * están en el futuro). Una tarea los crea en recordatorios_cita y otra manda los que ya vencieron. Antes de
 * enviar, cada recordatorio se "reclama" con un UPDATE condicional: si hay dos instancias del backend
 * (tu computadora y Railway), solo una lo envía. Si la cita se canceló, el recordatorio se borra sin enviar.
 *
 * <b>Avisos inmediatos.</b> Cuando una cita queda confirmada, pagada a la espera del odontólogo o cancelada,
 * el paciente recibe un correo. Cada envío queda en registro_notificaciones.
 */
@Service
public class NotificacionService {

    private static final Logger log = LoggerFactory.getLogger(NotificacionService.class);

    static final List<Duration> ANTICIPACIONES = List.of(Duration.ofHours(24), Duration.ofHours(2));
    /** Las citas se revisan con 2 días de anticipación: suficiente para el recordatorio de 24 h. */
    static final Duration VENTANA = Duration.ofDays(2);
    static final int LOTE = 50;

    private static final ZoneId ZONA = ZoneId.of("America/Lima");
    private static final Locale ES = Locale.of("es", "PE");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private final RecordatorioCitaRepository recordatorioRepository;
    private final RegistroNotificacionRepository registroRepository;
    private final CitaRepository citaRepository;
    private final EnviadorCorreo enviador;
    private final PlantillasCorreo plantillas;
    private final Clock clock;
    private final TransactionTemplate transaccion;

    public NotificacionService(RecordatorioCitaRepository recordatorioRepository,
                               RegistroNotificacionRepository registroRepository,
                               CitaRepository citaRepository, EnviadorCorreo enviador, PlantillasCorreo plantillas,
                               Clock clock, PlatformTransactionManager transactionManager) {
        this.recordatorioRepository = recordatorioRepository;
        this.registroRepository = registroRepository;
        this.citaRepository = citaRepository;
        this.enviador = enviador;
        this.plantillas = plantillas;
        this.clock = clock;
        this.transaccion = new TransactionTemplate(transactionManager);
    }

    /** Un correo listo para salir. */
    record Correo(UUID usuarioId, String para, String nombre, String asunto, String html, TipoNotificacion tipo) {}

    // ==================================================================
    // Recordatorios
    // ==================================================================

    /** Crea los recordatorios de las citas confirmadas de los próximos 2 días que aún no los tienen. */
    public int programarRecordatorios() {
        Integer creados = transaccion.execute(estado -> {
            OffsetDateTime ahora = OffsetDateTime.now(clock);
            int total = 0;
            for (Cita cita : citaRepository.sinRecordatorios(EstadoCita.CONFIRMADA, ahora, ahora.plus(VENTANA))) {
                for (Duration anticipacion : ANTICIPACIONES) {
                    OffsetDateTime cuando = cita.getInicioProgramado().minus(anticipacion);
                    if (cuando.isAfter(ahora)) {
                        recordatorioRepository.save(RecordatorioCita.builder()
                                .cita(cita).canal(CanalNotificacion.EMAIL).programadoPara(cuando).build());
                        total++;
                    }
                }
            }
            return total;
        });
        return creados == null ? 0 : creados;
    }

    /** Manda los recordatorios cuya hora ya llegó. Sin proveedor de correo no hace nada (quedan pendientes). */
    public int enviarPendientes() {
        if (!enviador.activo()) {
            return 0;
        }
        OffsetDateTime ahora = OffsetDateTime.now(clock);
        List<UUID> ids = transaccion.execute(estado ->
                recordatorioRepository.vencidos(EstadoNotificacion.PENDIENTE, ahora, PageRequest.of(0, LOTE)));
        int enviados = 0;
        for (UUID id : ids == null ? List.<UUID>of() : ids) {
            if (procesarRecordatorio(id, ahora)) {
                enviados++;
            }
        }
        return enviados;
    }

    private boolean procesarRecordatorio(UUID id, OffsetDateTime ahora) {
        // 1) En una transacción corta: revisar la cita, reclamar el recordatorio y armar el correo
        Optional<Correo> correo = transaccion.execute(estado -> {
            Optional<RecordatorioCita> encontrado = recordatorioRepository.conDetalle(id);
            if (encontrado.isEmpty()) {
                return Optional.<Correo>empty();
            }
            RecordatorioCita recordatorio = encontrado.get();
            Cita cita = recordatorio.getCita();
            if (cita.getEstado() != EstadoCita.CONFIRMADA) {
                recordatorioRepository.delete(recordatorio); // se canceló o reprogramó: ya no aplica
                return Optional.<Correo>empty();
            }
            if (!cita.getInicioProgramado().isAfter(ahora)) {
                // el backend estuvo apagado y la cita ya empezó: avisar ahora no sirve
                recordatorioRepository.cambiarEstadoSi(id, EstadoNotificacion.PENDIENTE, EstadoNotificacion.FALLIDA, null);
                return Optional.<Correo>empty();
            }
            if (recordatorioRepository.cambiarEstadoSi(id, EstadoNotificacion.PENDIENTE, EstadoNotificacion.ENVIADA, ahora) == 0) {
                return Optional.<Correo>empty(); // otra instancia ya lo tomó
            }
            return Optional.of(recordatorio(cita, ahora));
        });
        if (correo == null || correo.isEmpty()) {
            return false;
        }
        // 2) Fuera de la transacción: enviar (puede tardar unos segundos)
        boolean ok = enviar(correo.get());
        if (!ok) {
            transaccion.executeWithoutResult(estado -> recordatorioRepository.cambiarEstadoSi(
                    id, EstadoNotificacion.ENVIADA, EstadoNotificacion.FALLIDA, null));
        }
        return ok;
    }

    private Correo recordatorio(Cita cita, OffsetDateTime ahora) {
        Map<String, Object> v = datosDeLaCita(cita);
        long horas = Duration.between(ahora, cita.getInicioProgramado()).toHours();
        boolean esHoy = cita.getInicioProgramado().atZoneSameInstant(ZONA).toLocalDate()
                .equals(ahora.atZoneSameInstant(ZONA).toLocalDate());
        v.put("cuando", esHoy ? "hoy" : "mañana");
        v.put("enPocasHoras", horas < 6);
        String asunto = "Recordatorio: tu cita " + (esHoy ? "de hoy" : "de mañana") + " a las " + v.get("hora");
        return correoAlPaciente(cita, asunto, plantillas.armar("recordatorio", v), TipoNotificacion.RECORDATORIO_CITA);
    }

    // ==================================================================
    // Avisos inmediatos (al cambiar el estado de una cita)
    // ==================================================================

    /** Lo llama {@link AvisosCitas} después de que se guardó el cambio de estado. */
    public void avisarCambioDeEstado(CitaCambioEstado evento) {
        if (!enviador.activo()) {
            return;
        }
        Optional<Correo> correo = transaccion.execute(estado ->
                citaRepository.conDetalle(evento.citaId()).flatMap(cita -> avisoPara(cita, evento)));
        if (correo != null) {
            correo.ifPresent(this::enviar);
        }
    }

    private Optional<Correo> avisoPara(Cita cita, CitaCambioEstado evento) {
        Map<String, Object> v = datosDeLaCita(cita);
        return switch (evento.nuevo()) {
            case CONFIRMADA -> {
                v.put("pendienteDelOdontologo", false);
                yield Optional.of(correoAlPaciente(cita, "Tu cita está confirmada · " + v.get("fecha") + " a las " + v.get("hora"),
                        plantillas.armar("cita-confirmada", v), TipoNotificacion.CONFIRMACION_PAGO));
            }
            case PENDIENTE_CONFIRMACION -> {
                v.put("pendienteDelOdontologo", true);
                yield Optional.of(correoAlPaciente(cita, "Recibimos tu pago · falta la confirmación del odontólogo",
                        plantillas.armar("cita-confirmada", v), TipoNotificacion.CONFIRMACION_PAGO));
            }
            case CANCELADA_CON_REEMBOLSO, CANCELADA_SIN_REEMBOLSO -> {
                v.put("conReembolso", evento.nuevo() == EstadoCita.CANCELADA_CON_REEMBOLSO);
                v.put("eraPendienteDePago", evento.anterior() == EstadoCita.PENDIENTE_PAGO);
                v.put("motivo", cita.getMotivoCancelacion());
                yield Optional.of(correoAlPaciente(cita, "Tu cita del " + v.get("fecha") + " fue cancelada",
                        plantillas.armar("cita-cancelada", v), TipoNotificacion.AVISO_CANCELACION));
            }
            default -> Optional.empty();
        };
    }

    // ==================================================================
    // Apoyo
    // ==================================================================

    private static Map<String, Object> datosDeLaCita(Cita cita) {
        Map<String, Object> v = new HashMap<>();
        var inicio = cita.getInicioProgramado().atZoneSameInstant(ZONA);
        var odontologo = cita.getOdontologo();
        v.put("paciente", primerNombre(cita.getPaciente().getNombreCompleto()));
        v.put("odontologo", odontologo.getUsuario().getNombreCompleto());
        v.put("consultorio", odontologo.getNombreConsultorio());
        v.put("direccion", odontologo.getDireccionConsultorio());
        v.put("distrito", odontologo.getDistritoConsultorio());
        v.put("servicio", cita.getServicio().getTitulo());
        v.put("fecha", FECHA.format(inicio));
        v.put("hora", HORA.format(inicio));
        v.put("precio", soles(cita.getPrecioServicio()));
        v.put("deposito", soles(cita.getMontoDepositoRequerido()));
        return v;
    }

    private static Correo correoAlPaciente(Cita cita, String asunto, String html, TipoNotificacion tipo) {
        var paciente = cita.getPaciente();
        return new Correo(paciente.getId(), paciente.getCorreo(), paciente.getNombreCompleto(), asunto, html, tipo);
    }

    /** Envía y deja el rastro en registro_notificaciones. Nunca lanza: un correo caído no rompe nada más. */
    private boolean enviar(Correo correo) {
        boolean ok;
        try {
            enviador.enviar(correo.para(), correo.nombre(), correo.asunto(), correo.html());
            ok = true;
        } catch (RuntimeException e) {
            log.warn("No se pudo enviar el correo \"{}\" a {}: {}", correo.asunto(), correo.para(), e.getMessage());
            ok = false;
        }
        boolean enviado = ok;
        try {
            transaccion.executeWithoutResult(estado -> registroRepository.save(RegistroNotificacion.builder()
                    .usuarioId(correo.usuarioId()).tipo(correo.tipo()).canal(CanalNotificacion.EMAIL)
                    .estado(enviado ? EstadoNotificacion.ENVIADA : EstadoNotificacion.FALLIDA)
                    .enviadoEn(enviado ? OffsetDateTime.now(clock) : null)
                    .build()));
        } catch (RuntimeException e) {
            log.warn("No se pudo registrar la notificación: {}", e.getMessage());
        }
        return ok;
    }

    private static String primerNombre(String nombreCompleto) {
        return nombreCompleto == null || nombreCompleto.isBlank() ? "" : nombreCompleto.trim().split("\\s+")[0];
    }

    private static String soles(BigDecimal monto) {
        return monto == null ? null : "S/ " + monto.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

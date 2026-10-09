package com.odontosystem.OdontoSystem.odontologos;

import com.odontosystem.OdontoSystem.archivos.ArchivosResponses.FotoConsultorioResponse;
import com.odontosystem.OdontoSystem.entity.DiaSemana;
import com.odontosystem.OdontoSystem.entity.EstadoVerificacionOdontologo;
import com.odontosystem.OdontoSystem.entity.FrecuenciaDisponibilidad;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Peticiones y respuestas del módulo de odontólogos (JSON en snake_case). */
public final class OdontologoDtos {

    private OdontologoDtos() {
    }

    // ------------------------------------------------------------------
    // Catálogos
    // ------------------------------------------------------------------

    public record ItemCatalogo(Short id, String nombre) {}

    // ------------------------------------------------------------------
    // Búsqueda
    // ------------------------------------------------------------------

    /** Filtros de GET /api/odontologos. Todos opcionales. */
    public record FiltroBusqueda(String distrito,
                                 Short especialidadId,
                                 Short categoriaId,
                                 String q,
                                 Double lat,
                                 Double lng,
                                 Double radioKm,
                                 String orden,
                                 int pagina,
                                 int tamano) {}

    /** Tarjeta de un odontólogo en el listado. distancia_km solo viene cuando se busca "cerca de mí". */
    public record OdontologoTarjeta(UUID id,
                                    String nombre,
                                    String fotoUrl,
                                    String consultorio,
                                    String distrito,
                                    BigDecimal calificacion,
                                    Integer totalResenas,
                                    List<String> especialidades,
                                    BigDecimal precioDesde,
                                    Double distanciaKm) {}

    public record Pagina<T>(List<T> contenido, int pagina, int tamano, long totalElementos, int totalPaginas) {}

    // ------------------------------------------------------------------
    // Perfil
    // ------------------------------------------------------------------

    public record ServicioResponse(UUID id,
                                   Short categoriaId,
                                   String categoria,
                                   String titulo,
                                   String descripcion,
                                   BigDecimal precioTotal,
                                   BigDecimal montoDeposito,
                                   short duracionMinutos,
                                   boolean activo) {}

    public record HorarioDto(@NotNull DiaSemana diaSemana,
                             @NotNull LocalTime horaInicio,
                             @NotNull LocalTime horaFin,
                             @Min(5) @Max(240) Short intervaloMinutos) {}

    public record PerfilPublicoResponse(UUID id,
                                        String nombre,
                                        String fotoUrl,
                                        String consultorio,
                                        String direccion,
                                        String distrito,
                                        BigDecimal latitud,
                                        BigDecimal longitud,
                                        String numeroColegiatura,
                                        boolean colegiaturaVerificada,
                                        BigDecimal calificacion,
                                        Integer totalResenas,
                                        boolean requiereConfirmacionManual,
                                        List<ItemCatalogo> especialidades,
                                        List<ServicioResponse> servicios,
                                        List<HorarioDto> horarios,
                                        List<FotoConsultorioResponse> fotos) {}

    /** Lo que ve el propio odontólogo: el perfil público más su estado de verificación y datos de cobro. */
    public record MiPerfilResponse(PerfilPublicoResponse perfil,
                                   EstadoVerificacionOdontologo estadoVerificacion,
                                   String notaObservacion,
                                   String ruc,
                                   String cci,
                                   FrecuenciaDisponibilidad frecuenciaDisponibilidad,
                                   OffsetDateTime disponibilidadActualizadaEn) {}

    /** Actualización parcial: los campos en null no se tocan; un texto vacío borra el valor. */
    public record ActualizarPerfilRequest(@Size(max = 150) String nombreConsultorio,
                                          @Size(max = 255) String direccionConsultorio,
                                          @Size(max = 100) String distritoConsultorio,
                                          @DecimalMin("-90") @DecimalMax("90") BigDecimal latitud,
                                          @DecimalMin("-180") @DecimalMax("180") BigDecimal longitud,
                                          List<Short> especialidadIds,
                                          Boolean requiereConfirmacionManual,
                                          FrecuenciaDisponibilidad frecuenciaDisponibilidad,
                                          @Pattern(regexp = "^$|\\d{11}", message = "El RUC tiene 11 dígitos") String ruc,
                                          @Pattern(regexp = "^$|\\d{20}", message = "El CCI tiene 20 dígitos") String cci) {}

    // ------------------------------------------------------------------
    // Servicios, horarios y bloqueos del odontólogo
    // ------------------------------------------------------------------

    public record ServicioRequest(@NotNull Short categoriaId,
                                  @NotBlank @Size(max = 150) String titulo,
                                  @Size(max = 2000) String descripcion,
                                  @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal precioTotal,
                                  @NotNull @Min(5) @Max(480) Short duracionMinutos) {}

    public record HorariosRequest(@NotNull @Size(max = 50) List<@Valid HorarioDto> horarios) {}

    public record BloqueoRequest(@NotNull OffsetDateTime inicio,
                                 @NotNull OffsetDateTime fin,
                                 @Size(max = 150) String motivo) {}

    public record BloqueoResponse(UUID id, OffsetDateTime inicio, OffsetDateTime fin, String motivo) {}
}

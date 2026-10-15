package com.odontosystem.OdontoSystem.liquidaciones;

import com.odontosystem.OdontoSystem.citas.ParametrosSistema;
import com.odontosystem.OdontoSystem.entity.Cita;
import com.odontosystem.OdontoSystem.entity.EstadoApelacion;
import com.odontosystem.OdontoSystem.entity.EstadoCita;
import com.odontosystem.OdontoSystem.entity.EstadoLiquidacion;
import com.odontosystem.OdontoSystem.entity.EstadoPago;
import com.odontosystem.OdontoSystem.entity.Liquidacion;
import com.odontosystem.OdontoSystem.entity.Pago;
import com.odontosystem.OdontoSystem.entity.PerfilOdontologo;
import com.odontosystem.OdontoSystem.entity.Usuario;
import com.odontosystem.OdontoSystem.liquidaciones.LiquidacionService.MisIngresos;
import com.odontosystem.OdontoSystem.liquidaciones.LiquidacionService.ResultadoProceso;
import com.odontosystem.OdontoSystem.repository.ApelacionRepository;
import com.odontosystem.OdontoSystem.repository.LiquidacionRepository;
import com.odontosystem.OdontoSystem.repository.PagoRepository;
import com.odontosystem.OdontoSystem.repository.ReclamoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiquidacionServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-15T15:00:00Z");

    @Mock private LiquidacionRepository liquidaciones;
    @Mock private PagoRepository pagos;
    @Mock private ReclamoRepository reclamos;
    @Mock private ApelacionRepository apelaciones;
    @Mock private ParametrosSistema parametros;

    private LiquidacionService servicio;
    private PerfilOdontologo odontologo;

    @BeforeEach
    void setUp() {
        servicio = new LiquidacionService(liquidaciones, pagos, reclamos, apelaciones, parametros,
                Clock.fixed(AHORA, ZoneOffset.UTC));
        UUID id = UUID.randomUUID();
        odontologo = PerfilOdontologo.builder().usuarioId(id).usuario(Usuario.builder().id(id).build()).build();
        when(parametros.entero(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(liquidaciones.depositosSinLiquidar(any())).thenReturn(List.of());
        when(liquidaciones.save(any(Liquidacion.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static OffsetDateTime haceHoras(long horas) {
        return OffsetDateTime.ofInstant(AHORA.minusSeconds(horas * 3600), ZoneOffset.UTC);
    }

    private Cita cita(EstadoCita estado, OffsetDateTime inicio) {
        return Cita.builder().id(UUID.randomUUID()).odontologo(odontologo).estado(estado)
                .inicioProgramado(inicio).finProgramado(inicio.plusHours(1)).build();
    }

    private Liquidacion pendiente(Cita c, boolean pagoVerificado) {
        Liquidacion l = Liquidacion.builder().id(UUID.randomUUID()).odontologo(odontologo).cita(c)
                .monto(new BigDecimal("16.00")).build();
        when(pagos.findFirstByCita_IdAndEstado(c.getId(), EstadoPago.VERIFICADO))
                .thenReturn(pagoVerificado ? Optional.of(Pago.builder().cita(c).build()) : Optional.empty());
        return l;
    }

    @Test
    @DisplayName("Depósito de una cita terminada sin liquidar → se registra en custodia (PENDIENTE)")
    void registraEnCustodia() {
        Cita c = cita(EstadoCita.COMPLETADA, haceHoras(5));
        when(liquidaciones.depositosSinLiquidar(any())).thenReturn(List.of(
                Pago.builder().cita(c).monto(new BigDecimal("16.00")).estado(EstadoPago.VERIFICADO).build()));
        when(liquidaciones.enEstado(EstadoLiquidacion.PENDIENTE)).thenReturn(List.of());

        assertThat(servicio.procesar().registradas()).isEqualTo(1);
        ArgumentCaptor<Liquidacion> guardada = ArgumentCaptor.forClass(Liquidacion.class);
        verify(liquidaciones).save(guardada.capture());
        assertThat(guardada.getValue().getEstado()).isEqualTo(EstadoLiquidacion.PENDIENTE);
        assertThat(guardada.getValue().getMonto()).isEqualByComparingTo("16.00");
        assertThat(guardada.getValue().getOdontologo()).isSameAs(odontologo);
    }

    @Test
    @DisplayName("Pasadas 72 h de la cita y sin reclamos → se paga al odontólogo (sim_trf_); antes de 72 h, no")
    void pagaA72Horas() {
        Liquidacion vieja = pendiente(cita(EstadoCita.COMPLETADA, haceHoras(80)), true);
        Liquidacion reciente = pendiente(cita(EstadoCita.COMPLETADA, haceHoras(10)), true);
        when(liquidaciones.enEstado(EstadoLiquidacion.PENDIENTE)).thenReturn(List.of(vieja, reciente));

        ResultadoProceso r = servicio.procesar();

        assertThat(r.pagadas()).isEqualTo(1);
        assertThat(vieja.getEstado()).isEqualTo(EstadoLiquidacion.LIQUIDADA);
        assertThat(vieja.getReferenciaPago()).startsWith("sim_trf_");
        assertThat(vieja.getLiquidadoEn()).isNotNull();
        assertThat(reciente.getEstado()).isEqualTo(EstadoLiquidacion.PENDIENTE);
    }

    @Test
    @DisplayName("Con un reclamo abierto o una apelación pendiente el pago se retiene")
    void retencion() {
        Cita conReclamo = cita(EstadoCita.COMPLETADA, haceHoras(100));
        Cita conApelacion = cita(EstadoCita.INASISTENCIA_PENALIZADA, haceHoras(24 * 8));
        Liquidacion a = pendiente(conReclamo, true);
        Liquidacion b = pendiente(conApelacion, true);
        when(reclamos.existsByCita_IdAndEstadoIn(eq(conReclamo.getId()), any())).thenReturn(true);
        when(apelaciones.existsByCita_IdAndEstado(conApelacion.getId(), EstadoApelacion.PENDIENTE)).thenReturn(true);
        when(liquidaciones.enEstado(EstadoLiquidacion.PENDIENTE)).thenReturn(List.of(a, b));

        assertThat(servicio.procesar().pagadas()).isZero();
        assertThat(a.getEstado()).isEqualTo(EstadoLiquidacion.PENDIENTE);
        assertThat(b.getEstado()).isEqualTo(EstadoLiquidacion.PENDIENTE);
    }

    @Test
    @DisplayName("Inasistencia: se espera a que venza el plazo de apelación (7 días), no solo 72 h")
    void inasistenciaEsperaApelacion() {
        Liquidacion cuatroDias = pendiente(cita(EstadoCita.INASISTENCIA_PENALIZADA, haceHoras(24 * 4)), true);
        Liquidacion ochoDias = pendiente(cita(EstadoCita.INASISTENCIA_PENALIZADA, haceHoras(24 * 8)), true);
        when(liquidaciones.enEstado(EstadoLiquidacion.PENDIENTE)).thenReturn(List.of(cuatroDias, ochoDias));

        servicio.procesar();

        assertThat(cuatroDias.getEstado()).isEqualTo(EstadoLiquidacion.PENDIENTE);
        assertThat(ochoDias.getEstado()).isEqualTo(EstadoLiquidacion.LIQUIDADA);
    }

    @Test
    @DisplayName("Si el depósito se devolvió al paciente (pago ya no VERIFICADO) → liquidación CANCELADA")
    void devueltoCancela() {
        Liquidacion l = pendiente(cita(EstadoCita.INASISTENCIA_PENALIZADA, haceHoras(30)), false);
        when(liquidaciones.enEstado(EstadoLiquidacion.PENDIENTE)).thenReturn(List.of(l));

        assertThat(servicio.procesar().canceladas()).isEqualTo(1);
        assertThat(l.getEstado()).isEqualTo(EstadoLiquidacion.CANCELADA);
        assertThat(l.getReferenciaPago()).isNull();
    }

    @Test
    @DisplayName("cancelarDeCita solo toca una liquidación PENDIENTE; una LIQUIDADA queda igual")
    void cancelarDeCita() {
        Cita c = cita(EstadoCita.COMPLETADA, haceHoras(5));
        Liquidacion pagada = Liquidacion.builder().cita(c).estado(EstadoLiquidacion.LIQUIDADA).build();
        when(liquidaciones.findFirstByCita_Id(c.getId())).thenReturn(Optional.of(pagada));
        servicio.cancelarDeCita(c.getId(), "x");
        verify(liquidaciones, never()).save(any());
        assertThat(servicio.yaPagada(c.getId())).isTrue();
    }

    @Test
    @DisplayName("Mis ingresos: suma lo que está en custodia, lo pagado y lo devuelto")
    void misIngresos() {
        Cita c1 = cita(EstadoCita.COMPLETADA, haceHoras(5));
        Cita c2 = cita(EstadoCita.COMPLETADA, haceHoras(100));
        Cita c3 = cita(EstadoCita.INASISTENCIA_PENALIZADA, haceHoras(100));
        when(liquidaciones.delOdontologo(odontologo.getUsuarioId())).thenReturn(List.of(
                Liquidacion.builder().cita(c1).monto(new BigDecimal("16.00")).build(),
                Liquidacion.builder().cita(c2).monto(new BigDecimal("20.00")).estado(EstadoLiquidacion.LIQUIDADA).build(),
                Liquidacion.builder().cita(c3).monto(new BigDecimal("10.00")).estado(EstadoLiquidacion.CANCELADA)
                        .motivoFallo("El depósito se devolvió al paciente").build()));

        MisIngresos m = servicio.delOdontologo(odontologo.getUsuarioId());

        assertThat(m.enCustodia()).isEqualByComparingTo("16.00");
        assertThat(m.pagado()).isEqualByComparingTo("20.00");
        assertThat(m.devueltoAPacientes()).isEqualByComparingTo("10.00");
        assertThat(m.liquidaciones().get(0).pagarDesde()).isEqualTo(c1.getFinProgramado().plusHours(72));
        assertThat(m.liquidaciones().get(2).retenidaPor()).contains("devolvió");
    }
}

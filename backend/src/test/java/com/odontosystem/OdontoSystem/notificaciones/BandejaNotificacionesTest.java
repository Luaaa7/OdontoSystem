package com.odontosystem.OdontoSystem.notificaciones;

import com.odontosystem.OdontoSystem.entity.NotificacionApp;
import com.odontosystem.OdontoSystem.notificaciones.BandejaNotificaciones.BandejaResponse;
import com.odontosystem.OdontoSystem.notificaciones.BandejaNotificaciones.NotificacionResponse;
import com.odontosystem.OdontoSystem.repository.NotificacionAppRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BandejaNotificacionesTest {

    private static final Instant AHORA = Instant.parse("2026-11-01T18:00:00Z");
    private static final OffsetDateTime AHORA_ODT = OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC);

    @Mock private NotificacionAppRepository repositorio;

    private BandejaNotificaciones bandeja;
    private final UUID usuario = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bandeja = new BandejaNotificaciones(repositorio, Clock.fixed(AHORA, ZoneOffset.UTC));
        when(repositorio.save(any(NotificacionApp.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("Guarda el aviso con la fecha en que ocurrió (no la de la cita) y sin leer")
    void registra() {
        UUID cita = UUID.randomUUID();

        bandeja.registrar(usuario, "AVISO_CANCELACION", "Cita cancelada", "Tu cita del lunes 2 de noviembre fue cancelada", cita);

        ArgumentCaptor<NotificacionApp> captor = ArgumentCaptor.forClass(NotificacionApp.class);
        verify(repositorio).save(captor.capture());
        NotificacionApp n = captor.getValue();
        assertThat(n.getUsuarioId()).isEqualTo(usuario);
        assertThat(n.getTipo()).isEqualTo("AVISO_CANCELACION");
        assertThat(n.getCitaId()).isEqualTo(cita);
        assertThat(n.getCreadoEn()).isEqualTo(AHORA_ODT);
        assertThat(n.getLeidaEn()).isNull();
    }

    @Test
    @DisplayName("Recorta textos largos, usa el título si no hay mensaje y no guarda avisos sin usuario o sin título")
    void recortaYValida() {
        bandeja.registrar(usuario, null, "T".repeat(200), null, null);
        ArgumentCaptor<NotificacionApp> captor = ArgumentCaptor.forClass(NotificacionApp.class);
        verify(repositorio).save(captor.capture());
        assertThat(captor.getValue().getTitulo()).hasSize(BandejaNotificaciones.LARGO_TITULO).endsWith("…");
        assertThat(captor.getValue().getMensaje()).hasSize(200);
        assertThat(captor.getValue().getTipo()).isEqualTo("AVISO");

        bandeja.registrar(null, "X", "Título", "m", null);
        bandeja.registrar(usuario, "X", " ", "m", null);
        verify(repositorio).save(any()); // solo el primero
    }

    @Test
    @DisplayName("Si la base falla, registrar no lanza")
    void registrarNoLanza() {
        when(repositorio.save(any())).thenThrow(new RuntimeException("BD caída"));

        bandeja.registrar(usuario, "X", "Título", "Mensaje", null);
    }

    @Test
    @DisplayName("Lista las recientes con cuántas no se han leído")
    void lista() {
        NotificacionApp leida = NotificacionApp.builder().id(UUID.randomUUID()).usuarioId(usuario).tipo("X")
                .titulo("A").mensaje("a").creadoEn(AHORA_ODT).leidaEn(AHORA_ODT).build();
        NotificacionApp nueva = NotificacionApp.builder().id(UUID.randomUUID()).usuarioId(usuario).tipo("X")
                .titulo("B").mensaje("b").creadoEn(AHORA_ODT).build();
        when(repositorio.recientes(any(), any())).thenReturn(List.of(nueva, leida));
        when(repositorio.countByUsuarioIdAndLeidaEnIsNull(usuario)).thenReturn(1L);

        BandejaResponse r = bandeja.deUsuario(usuario);

        assertThat(r.noLeidas()).isEqualTo(1);
        assertThat(r.notificaciones()).extracting(NotificacionResponse::leida).containsExactly(false, true);
    }

    @Test
    @DisplayName("Marcar como leída guarda la fecha; una ajena da 404")
    void marcarLeida() {
        NotificacionApp n = NotificacionApp.builder().id(UUID.randomUUID()).usuarioId(usuario).tipo("X")
                .titulo("A").mensaje("a").creadoEn(AHORA_ODT).build();
        when(repositorio.findByIdAndUsuarioId(n.getId(), usuario)).thenReturn(Optional.of(n));
        UUID ajena = UUID.randomUUID();
        when(repositorio.findByIdAndUsuarioId(ajena, usuario)).thenReturn(Optional.empty());

        assertThat(bandeja.marcarLeida(usuario, n.getId()).leida()).isTrue();
        assertThat(n.getLeidaEn()).isEqualTo(AHORA_ODT);
        assertThatThrownBy(() -> bandeja.marcarLeida(usuario, ajena))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("Una ya leída no se vuelve a guardar; marcar todas devuelve cuántas cambiaron")
    void yaLeidaYTodas() {
        NotificacionApp n = NotificacionApp.builder().id(UUID.randomUUID()).usuarioId(usuario).tipo("X")
                .titulo("A").mensaje("a").creadoEn(AHORA_ODT).leidaEn(AHORA_ODT.minusDays(1)).build();
        when(repositorio.findByIdAndUsuarioId(n.getId(), usuario)).thenReturn(Optional.of(n));
        when(repositorio.marcarTodasLeidas(usuario, AHORA_ODT)).thenReturn(3);

        bandeja.marcarLeida(usuario, n.getId());

        verify(repositorio, never()).save(any());
        assertThat(n.getLeidaEn()).isEqualTo(AHORA_ODT.minusDays(1));
        assertThat(bandeja.marcarTodasLeidas(usuario).marcadas()).isEqualTo(3);
    }
}

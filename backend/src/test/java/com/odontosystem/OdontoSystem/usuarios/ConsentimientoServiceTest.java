package com.odontosystem.OdontoSystem.usuarios;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsentimientoServiceTest {

    @Test
    @DisplayName("Solo se guarda una IP con forma válida (IPv4 o IPv6); cualquier otra cosa queda en null")
    void ipValida() {
        assertThat(ConsentimientoService.ipValida("190.12.3.4")).isEqualTo("190.12.3.4");
        assertThat(ConsentimientoService.ipValida("0:0:0:0:0:0:0:1")).isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(ConsentimientoService.ipValida(" 10.0.0.1 ")).isEqualTo("10.0.0.1");
        assertThat(ConsentimientoService.ipValida("1.2.3.4'; drop table x")).isNull();
        assertThat(ConsentimientoService.ipValida(null)).isNull();
    }

    @Test
    @DisplayName("La versión vigente sale de la configuración")
    void version() {
        assertThat(new ConsentimientoService(null, "2026-10-v1").versionVigente()).isEqualTo("2026-10-v1");
    }

    private static Query consultaSimulada(EntityManager em) {
        Query query = mock(Query.class, RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        return query;
    }

    @Test
    @DisplayName("Registrar guarda la versión vigente, el usuario y la IP limpia")
    void registra() {
        EntityManager em = mock(EntityManager.class);
        Query query = consultaSimulada(em);
        UUID usuario = UUID.randomUUID();

        new ConsentimientoService(em, "2026-10-v1").registrar(usuario, " 190.12.3.4 ");

        verify(query).setParameter("usuario", usuario);
        verify(query).setParameter("version", "2026-10-v1");
        verify(query).setParameter("ip", "190.12.3.4");
        verify(query).executeUpdate();
    }

    @Test
    @DisplayName("Si la IP no es válida, el consentimiento se guarda igual pero con IP vacía (null)")
    void registraConIpInvalida() {
        EntityManager em = mock(EntityManager.class);
        Query query = consultaSimulada(em);

        new ConsentimientoService(em, "2026-10-v1").registrar(UUID.randomUUID(), "no-es-una-ip!");

        verify(query).setParameter("ip", (Object) null);
        verify(query).executeUpdate();
    }

    @Test
    @DisplayName("Lista los consentimientos del usuario con su fecha")
    void lista() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class, RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        Instant cuando = Instant.parse("2026-10-15T12:00:00Z");
        when(query.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{"2026-10-v1", Timestamp.from(cuando)},
                new Object[]{"2026-01-v0", cuando}));

        List<ConsentimientoService.ConsentimientoResponse> r =
                new ConsentimientoService(em, "2026-10-v1").deUsuario(UUID.randomUUID());

        assertThat(r).hasSize(2);
        assertThat(r.get(0).versionPolitica()).isEqualTo("2026-10-v1");
        assertThat(r.get(0).aceptadoEn().toInstant()).isEqualTo(cuando);
        assertThat(r.get(1).aceptadoEn().toInstant()).isEqualTo(cuando);
    }
}

package com.odontosystem.OdontoSystem.notificaciones;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Sin proveedor (CORREO_PROVEEDOR=ninguno o vacío): no sale ningún correo. */
@Component
@ConditionalOnProperty(name = "app.correo.proveedor", havingValue = "ninguno", matchIfMissing = true)
public class EnviadorDesactivado implements EnviadorCorreo {

    private static final Logger log = LoggerFactory.getLogger(EnviadorDesactivado.class);

    public EnviadorDesactivado() {
        log.info("Correo desactivado (CORREO_PROVEEDOR=ninguno): no se enviarán recordatorios ni avisos");
    }

    @Override
    public boolean activo() {
        return false;
    }

    @Override
    public void enviar(String para, String nombre, String asunto, String html) {
        log.debug("Correo no enviado (sin proveedor): {} a {}", asunto, para);
    }
}

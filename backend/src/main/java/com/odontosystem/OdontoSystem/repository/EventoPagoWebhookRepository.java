package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.EventoPagoWebhook;
import com.odontosystem.OdontoSystem.entity.PasarelaPago;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EventoPagoWebhookRepository extends JpaRepository<EventoPagoWebhook, UUID> {

    /** Para no procesar dos veces el mismo aviso de la pasarela. */
    boolean existsByPasarelaPagoAndEventoExternoId(PasarelaPago pasarelaPago, String eventoExternoId);
}

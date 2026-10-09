package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.EventoPagoWebhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EventoPagoWebhookRepository extends JpaRepository<EventoPagoWebhook, UUID> {
}

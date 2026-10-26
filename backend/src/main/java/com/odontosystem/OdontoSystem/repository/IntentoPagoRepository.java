package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.IntentoPago;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IntentoPagoRepository extends JpaRepository<IntentoPago, UUID> {
}

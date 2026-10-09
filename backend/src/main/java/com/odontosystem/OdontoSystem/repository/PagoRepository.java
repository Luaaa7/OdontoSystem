package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.Pago;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PagoRepository extends JpaRepository<Pago, UUID> {
}

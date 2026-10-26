package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.RegistroNotificacion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RegistroNotificacionRepository extends JpaRepository<RegistroNotificacion, UUID> {
}

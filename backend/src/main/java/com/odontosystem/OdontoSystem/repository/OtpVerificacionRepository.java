package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.OtpVerificacion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OtpVerificacionRepository extends JpaRepository<OtpVerificacion, UUID> {

    /** El último código enviado a ese correo (para no mandar más de uno por minuto). */
    Optional<OtpVerificacion> findFirstByDestinoIgnoreCaseOrderByCreadoEnDesc(String destino);

    /** El último código sin usar del usuario: es el único que vale. */
    Optional<OtpVerificacion> findFirstByUsuarioIdAndConsumidoEnIsNullOrderByCreadoEnDesc(UUID usuarioId);
}

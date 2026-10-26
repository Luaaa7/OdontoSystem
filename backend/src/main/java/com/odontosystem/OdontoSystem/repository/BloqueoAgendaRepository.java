package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.BloqueoAgenda;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BloqueoAgendaRepository extends JpaRepository<BloqueoAgenda, UUID> {
    List<BloqueoAgenda> findByOdontologo_UsuarioIdOrderByInicioAsc(UUID odontologoId);

    List<BloqueoAgenda> findByOdontologo_UsuarioIdAndFinAfterOrderByInicioAsc(UUID odontologoId, java.time.OffsetDateTime desde);

    java.util.Optional<BloqueoAgenda> findByIdAndOdontologo_UsuarioId(UUID id, UUID odontologoId);

    /** Bloqueos que se cruzan con [desde, hasta): inicio antes de hasta y fin después de desde. */
    List<BloqueoAgenda> findByOdontologo_UsuarioIdAndInicioBeforeAndFinAfter(UUID odontologoId,
                                                                             java.time.OffsetDateTime hasta,
                                                                             java.time.OffsetDateTime desde);
}
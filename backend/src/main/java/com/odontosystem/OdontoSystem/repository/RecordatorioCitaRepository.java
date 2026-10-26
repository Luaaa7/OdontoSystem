package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.EstadoNotificacion;
import com.odontosystem.OdontoSystem.entity.RecordatorioCita;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordatorioCitaRepository extends JpaRepository<RecordatorioCita, UUID> {

    /** Ids de los recordatorios en ese estado cuya hora ya llegó, los más antiguos primero. */
    @Query("select r.id from RecordatorioCita r where r.estado = :estado and r.programadoPara <= :ahora "
            + "order by r.programadoPara asc")
    List<UUID> vencidos(@Param("estado") EstadoNotificacion estado, @Param("ahora") OffsetDateTime ahora, Pageable pagina);

    /** El recordatorio con todo lo que hace falta para armar el correo. */
    @Query("select r from RecordatorioCita r join fetch r.cita c join fetch c.paciente join fetch c.odontologo o "
            + "join fetch o.usuario join fetch c.servicio where r.id = :id")
    Optional<RecordatorioCita> conDetalle(@Param("id") UUID id);

    /**
     * Cambia el estado solo si sigue en el esperado. Devuelve 1 si lo cambió y 0 si otro proceso
     * (por ejemplo, otra instancia del backend) ya lo había tomado: así un recordatorio no sale dos veces.
     */
    @Modifying
    @Query("update RecordatorioCita r set r.estado = :nuevo, r.enviadoEn = :enviadoEn where r.id = :id and r.estado = :actual")
    int cambiarEstadoSi(@Param("id") UUID id, @Param("actual") EstadoNotificacion actual,
                        @Param("nuevo") EstadoNotificacion nuevo, @Param("enviadoEn") OffsetDateTime enviadoEn);
}

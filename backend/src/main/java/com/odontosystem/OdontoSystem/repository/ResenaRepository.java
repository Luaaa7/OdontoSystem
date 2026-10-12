package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.Resena;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ResenaRepository extends JpaRepository<Resena, Long> {

    boolean existsByCita_Id(UUID citaId);

    /** Reseñas de un odontólogo, las más recientes primero; trae al paciente en la misma consulta. */
    @Query(value = "select r from Resena r join fetch r.paciente "
            + "where r.odontologo.usuarioId = :odontologoId order by r.creadoEn desc",
            countQuery = "select count(r) from Resena r where r.odontologo.usuarioId = :odontologoId")
    Page<Resena> delOdontologo(@Param("odontologoId") UUID odontologoId, Pageable pageable);
}
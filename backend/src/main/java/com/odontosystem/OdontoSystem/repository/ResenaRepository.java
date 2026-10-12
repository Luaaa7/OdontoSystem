package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.Resena;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResenaRepository extends JpaRepository<Resena, Long> {

    boolean existsByCita_Id(UUID citaId);

    Optional<Resena> findByCita_IdAndPaciente_Id(UUID citaId, UUID pacienteId);

    /** Reseñas de un odontólogo, las más recientes primero (con el paciente para mostrar su nombre). */
    @Query(value = "select r from Resena r join fetch r.paciente where r.odontologoId = :odontologoId order by r.creadoEn desc",
            countQuery = "select count(r) from Resena r where r.odontologoId = :odontologoId")
    Page<Resena> delOdontologo(@Param("odontologoId") UUID odontologoId, Pageable pagina);

    /** Cuántas reseñas tiene de cada puntaje: filas [calificacion, cantidad]. */
    @Query("select r.calificacion, count(r) from Resena r where r.odontologoId = :odontologoId group by r.calificacion")
    List<Object[]> distribucion(@Param("odontologoId") UUID odontologoId);
}

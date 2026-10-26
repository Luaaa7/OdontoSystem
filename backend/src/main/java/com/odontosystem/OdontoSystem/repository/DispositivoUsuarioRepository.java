package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.DispositivoUsuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DispositivoUsuarioRepository extends JpaRepository<DispositivoUsuario, Long> {

    List<DispositivoUsuario> findByUsuarioId(UUID usuarioId);

    Optional<DispositivoUsuario> findByUsuarioIdAndTokenFcm(UUID usuarioId, String tokenFcm);

    /** Un token que Firebase dio por inválido no sirve para nadie: se borra en todas las cuentas. */
    @Modifying
    @Query("delete from DispositivoUsuario d where d.tokenFcm = :token")
    int borrarToken(@Param("token") String token);

    @Modifying
    @Query("delete from DispositivoUsuario d where d.usuarioId = :usuarioId and d.tokenFcm = :token")
    int borrarDeUsuario(@Param("usuarioId") UUID usuarioId, @Param("token") String token);
}

package com.odontosystem.OdontoSystem.repository;

import com.odontosystem.OdontoSystem.entity.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    Optional<Usuario> findByCorreo(String correo);

    Optional<Usuario> findByNumeroDocumento(String numeroDocumento);

    Optional<Usuario> findByGoogleOauthSub(String googleOauthSub);

    boolean existsByCorreo(String correo);

    boolean existsByNumeroDocumento(String numeroDocumento);

    boolean existsByTelefono(String telefono);
}
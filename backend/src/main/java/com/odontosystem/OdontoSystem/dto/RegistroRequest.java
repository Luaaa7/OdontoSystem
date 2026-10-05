package com.odontosystem.OdontoSystem.dto;

import com.odontosystem.OdontoSystem.entity.RolUsuario;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegistroRequest(
        @NotBlank @Size(max = 20) String numeroDocumento,
        @NotBlank @Size(max = 5) String tipoDocumento,
        @NotBlank @Size(max = 150) String nombreCompleto,
        @NotBlank @Size(max = 20) String telefono,
        @NotBlank @Email @Size(max = 150) String correo,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotNull RolUsuario rol,
        @Size(max = 30) String numeroColegiatura // obligatorio solo si rol=ODONTOLOGO (validado en AuthService)
) {}
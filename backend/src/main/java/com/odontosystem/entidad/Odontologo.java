package com.odontosystem.entidad;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "odontologos")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Odontologo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Un odontologo ES un usuario con rol ODONTOLOGO; aqui va su info profesional
    @OneToOne
    @JoinColumn(name = "usuario_id", nullable = false, unique = true)
    private Usuario usuario;

    @ManyToOne
    @JoinColumn(name = "distrito_id", nullable = false)
    private Distrito distrito;

    private String especialidad; // ej: "Ortodoncia", "General", "Endodoncia"

    private String direccionConsultorio;

    private Double rating = 0.0;

    // Coincide con Usuario.aprobado, pero lo dejamos explicito aqui
    // para que la busqueda del marketplace solo muestre perfiles listos
    private boolean perfilCompleto = false;
}

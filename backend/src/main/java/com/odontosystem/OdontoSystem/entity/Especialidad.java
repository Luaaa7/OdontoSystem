package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Catálogo de especialidades (tabla especialidades). Solo lectura: lo carga la migración V1. */
@Entity
@Table(name = "especialidades")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Especialidad {

    @Id
    private Short id;

    @Column(name = "nombre", nullable = false, length = 100)
    private String nombre;
}

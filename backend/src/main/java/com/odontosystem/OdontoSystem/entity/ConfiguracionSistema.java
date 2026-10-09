package com.odontosystem.OdontoSystem.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Parámetros del negocio (tabla configuracion_sistema): tolerancia, strikes, horas de cancelación, etc. */
@Entity
@Table(name = "configuracion_sistema")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ConfiguracionSistema {

    @Id
    @Column(name = "clave_parametro", length = 50)
    private String clave;

    @Column(name = "valor_parametro", nullable = false, length = 255)
    private String valor;
}

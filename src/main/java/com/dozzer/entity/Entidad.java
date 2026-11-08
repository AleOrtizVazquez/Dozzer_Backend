package com.dozzer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cat_entidades")
public class Entidad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_entidad")
    private Long idEntidad;

    @Column(name = "nombre_entidad", nullable = false, length = 100)
    private String nombreEntidad;

    @Column(name = "abreviatura", nullable = false, length = 20)
    private String abreviatura;

    @Column(name = "estatus", nullable = false)
    private Boolean estatus = true;
}

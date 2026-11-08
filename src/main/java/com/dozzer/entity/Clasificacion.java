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
@Table(name = "cat_clasificaciones")
public class Clasificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_clasificacion")
    private Long idClasificacion;

    @Column(name = "codigo", length = 40)
    private String codigo;

    @Column(name = "nombre", nullable = false, unique = true, length = 150)
    private String nombre;

}

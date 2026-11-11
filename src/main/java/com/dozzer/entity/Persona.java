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
@Table(name = "cat_personas")
public class Persona {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_persona")
    private Long idPersona;

    @Column(name = "nombre_completo", nullable = false, length = 180)
    private String nombreCompleto;

    @Column(name = "correo", length = 180)
    private String correo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_puesto")
    private PuestoDepartamento puesto;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_sede")
    private Sede sede;

    @Column(name = "estatus")
    private Boolean estatus = true;

    @Column(name = "numero_empleado", length = 20)
    private String numeroEmpleado;

    // Encargado de movimientos de inventario de su área (marcado en amarillo en PERSONAL DOZZER)
    @Column(name = "es_encargado", nullable = false)
    private Boolean esEncargado = false;

}

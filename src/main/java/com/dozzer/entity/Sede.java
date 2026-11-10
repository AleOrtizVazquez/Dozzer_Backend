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
@Table(name = "cat_sedes")
public class Sede {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_sede")
    private Long idSede;

    @Column(name = "entidad", length = 120)
    private String entidad;

    @Column(name = "nombre_sede", nullable = false, length = 150)
    private String nombreSede;

    @Column(name = "prefijo_clave", length = 20)
    private String prefijoClave;

    @Column(name = "direccion_completa", length = 300)
    private String direccionCompleta;

    @Column(name = "estatus")
    private Boolean estatus = true;

}

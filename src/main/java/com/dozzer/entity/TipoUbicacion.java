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
@Table(name = "cat_tipos_ubicacion")
public class TipoUbicacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_tipo_ubicacion")
    private Long idTipoUbicacion;

    @Column(name = "nombre_tipo_ubicacion", nullable = false, length = 150)
    private String nombreTipoUbicacion;

    @Column(name = "es_temporal", nullable = false)
    private Boolean esTemporal = false;

    @Column(name = "estatus", nullable = false)
    private Boolean estatus = true;
}

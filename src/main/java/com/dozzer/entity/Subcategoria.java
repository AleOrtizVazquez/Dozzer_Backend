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
@Table(name = "cat_subcategorias")
public class Subcategoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_subcategoria")
    private Long idSubcategoria;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_clasificacion", nullable = false)
    private Clasificacion clasificacion;

    @Column(name = "nombre_subcategoria_acta", nullable = false, length = 150)
    private String nombreSubcategoria;

    // Conteos tomados del acta de inventario (solo informativos)
    @Column(name = "conteo_sede_celaya", nullable = false)
    private Integer conteoSedeCelaya = 0;

    @Column(name = "conteo_total_empresa", nullable = false)
    private Integer conteoTotalEmpresa = 0;
}

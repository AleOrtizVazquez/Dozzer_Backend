package com.dozzer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "bienes")
public class Bien {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_bien")
    private Long idBien;

    @Column(name = "clave", nullable = false, unique = true, length = 80)
    private String clave;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_bien_padre")
    private Bien bienPadre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_clasificacion")
    private Clasificacion clasificacion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_subcategoria")
    private Subcategoria subcategoria;

    @Column(name = "articulo", nullable = false, length = 180)
    private String articulo;

    @Column(name = "caracteristicas", columnDefinition = "TEXT")
    private String caracteristicas;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_marca")
    private Marca marca;

    @Column(name = "modelo", length = 120)
    private String modelo;

    @Column(name = "numero_serie", length = 150)
    private String numeroSerie;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_color")
    private Color color;

    @Column(name = "unidad_medida", length = 40)
    private String unidadMedida;

    @Column(name = "cantidad_piezas")
    private Integer cantidadPiezas = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_responsable_area")
    private Persona responsableArea;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_usuario_directo")
    private Persona usuarioDirecto;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_ubicacion")
    private Ubicacion ubicacion;

    @Column(name = "estatus_inventario", length = 40)
    private String estatusInventario = "ACTIVO";

    @Column(name = "estatus_asignacion", length = 40)
    private String estatusAsignacion = "SIN_ASIGNAR";

    @Column(name = "estado_fisico", length = 40)
    private String estadoFisico;

    @Column(name = "fecha_alta")
    private LocalDate fechaAlta;

    @Column(name = "activo")
    private Boolean activo = true;

    // Calibración (verde en el Excel original) y mantenimiento (azul), con la próxima fecha
    @Column(name = "requiere_calibracion", nullable = false)
    private Boolean requiereCalibracion = false;

    @Column(name = "fecha_calibracion")
    private LocalDate fechaCalibracion;

    @Column(name = "requiere_mantenimiento", nullable = false)
    private Boolean requiereMantenimiento = false;

    @Column(name = "fecha_mantenimiento")
    private LocalDate fechaMantenimiento;

    @Column(name = "fecha_actualizacion")
    private LocalDateTime fechaActualizacion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_usuario_registro")
    private Usuario usuarioRegistro;

    // Identificador permanente de la etiqueta QR: se asigna al crear el bien y nunca se actualiza
    @Column(name = "codigo_qr", nullable = false, unique = true, length = 36, updatable = false)
    private String codigoQr;

    // Foto del bien (se toma una sola vez en el inventario anual)
    @Column(name = "foto_key", length = 300)
    private String fotoKey;

    @Column(name = "foto_fecha")
    private LocalDateTime fotoFecha;

    @PrePersist
    protected void antesDeGuardar() {

        if (codigoQr == null) {
            codigoQr = java.util.UUID.randomUUID().toString();
        }

        if (fechaAlta == null) {
            fechaAlta = LocalDate.now();
        }

        if (activo == null) {
            activo = true;
        }

        if (cantidadPiezas == null) {
            cantidadPiezas = 1;
        }

        if (estatusInventario == null) {
            estatusInventario = "ACTIVO";
        }

        if (estatusAsignacion == null) {
            estatusAsignacion = "SIN_ASIGNAR";
        }
    }

    @PreUpdate
    protected void antesDeActualizar() {
        fechaActualizacion = LocalDateTime.now();
    }
}

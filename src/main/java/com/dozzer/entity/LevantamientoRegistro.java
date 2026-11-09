package com.dozzer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// Un bien revisado durante el levantamiento
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "levantamiento_registros")
public class LevantamientoRegistro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_registro")
    private Long idRegistro;

    @Column(name = "id_levantamiento", nullable = false)
    private Long idLevantamiento;

    @Column(name = "id_bien", nullable = false)
    private Long idBien;

    @Column(name = "respuesta", length = 60)
    private String respuesta;

    @Column(name = "id_ubicacion_encontrada")
    private Long idUbicacionEncontrada;

    @Column(name = "fuera_de_lugar")
    private Boolean fueraDeLugar = false;

    @Column(name = "foto_tomada")
    private Boolean fotoTomada = false;

    @Column(name = "id_usuario")
    private Long idUsuario;

    @Column(name = "fecha", nullable = false)
    private LocalDateTime fecha;
}

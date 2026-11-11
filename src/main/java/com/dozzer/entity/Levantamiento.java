package com.dozzer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

// Inventario anual: levantamiento físico de una sede
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "levantamientos")
public class Levantamiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_levantamiento")
    private Long idLevantamiento;

    @Column(name = "id_sede", nullable = false)
    private Long idSede;

    @Column(name = "anio", nullable = false)
    private Integer anio;

    // Semestre: 1 (enero-junio) o 2 (julio-diciembre). Uno por sede, año y semestre.
    @Column(name = "periodo", nullable = false)
    private Integer periodo = 1;

    // EN_CURSO o CERRADO
    @Column(name = "estado", nullable = false, length = 20)
    private String estado = "EN_CURSO";

    @Column(name = "ciudad", length = 150)
    private String ciudad;

    @Column(name = "direccion", length = 300)
    private String direccion;

    @Column(name = "fecha_inicio", nullable = false)
    private LocalDateTime fechaInicio;

    @Column(name = "fecha_cierre")
    private LocalDateTime fechaCierre;

    // [{nombre, cargo}] — los que firman el acta (observan)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "participantes", columnDefinition = "jsonb")
    private List<Map<String, Object>> participantes;

    // {texto, opciones:[...]} — la única pregunta por bien (configurable)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pregunta", columnDefinition = "jsonb")
    private Map<String, Object> pregunta;

    @Column(name = "id_usuario_capturista")
    private Long idUsuarioCapturista;

    @Column(name = "id_usuario_creo")
    private Long idUsuarioCreo;

    @Column(name = "id_documento_inicio")
    private Long idDocumentoInicio;

    @Column(name = "id_documento_cierre")
    private Long idDocumentoCierre;
}

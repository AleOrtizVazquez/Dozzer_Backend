
package com.dozzer.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "solicitudes_bienes")
public class SolicitudBien {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_solicitud")
    private Long idSolicitud;

    @Column(name = "tipo_operacion", nullable = false, length = 30)
    private String tipoOperacion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_bien")
    private Bien bien;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "datos_propuestos", nullable = false, columnDefinition = "jsonb")
    private Map<String,Object> datosPropuestos;

    @Column(name = "estado", nullable = false, length = 20)
    private String estado = "PENDIENTE";

    @Column(name = "motivo_solicitud", length = 500)
    private String motivoSolicitud;

    @Column(name = "motivo_resolucion", length = 500)
    private String motivoResolucion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_usuario_solicitante", nullable = false)
    private Usuario usuarioSolicitante;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_usuario_revisor")
    private Usuario usuarioRevisor;

    @Column(name = "fecha_solicitud", nullable = false)
    private LocalDateTime fechaSolicitud;

    @Column(name = "fecha_resolucion")
    private LocalDateTime fechaResolucion;

    @Column(name = "id_sede", nullable = false)
    private Long idSede;

    // Copia del bien antes de aplicar una MODIFICACION aprobada (permite revocarla)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "datos_anteriores", columnDefinition = "jsonb")
    private Map<String,Object> datosAnteriores;

    // Última revocación de la autorización (auditoría)
    @Column(name = "motivo_revocacion", length = 500)
    private String motivoRevocacion;

    @Column(name = "fecha_revocacion")
    private LocalDateTime fechaRevocacion;

    @Column(name = "id_usuario_revoco")
    private Long idUsuarioRevoco;

    @PrePersist
    protected void antesDeGuardar() {
        if (fechaSolicitud == null) {
            fechaSolicitud = LocalDateTime.now();
        }
        if (estado == null) {
            estado = "PENDIENTE";
        }
    }
}

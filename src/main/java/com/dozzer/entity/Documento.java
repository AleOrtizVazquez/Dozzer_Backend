package com.dozzer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

// Formato de Alta/Baja: guarda solo los datos congelados; el PDF se arma al abrirlo
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "documentos")
public class Documento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_documento")
    private Long idDocumento;

    @Column(name = "tipo", nullable = false, length = 20)
    private String tipo;

    @Column(name = "folio", length = 30, unique = true)
    private String folio;

    // VIGENTE, ANULADO (se revocó la autorización), BORRADOR (inventario en curso) o HISTORICO (resguardo reemplazado)
    @Column(name = "estado", nullable = false, length = 20)
    private String estado = "VIGENTE";

    @Column(name = "id_sede")
    private Long idSede;

    @Column(name = "id_bien")
    private Long idBien;

    @Column(name = "id_solicitud")
    private Long idSolicitud;

    @Column(name = "id_usuario_solicitante")
    private Long idUsuarioSolicitante;

    @Column(name = "id_usuario_autorizo")
    private Long idUsuarioAutorizo;

    // Resguardos y actas del inventario: levantamiento que los generó y persona del resguardo
    @Column(name = "id_levantamiento")
    private Long idLevantamiento;

    @Column(name = "id_persona")
    private Long idPersona;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "datos", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> datos;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    protected void antesDeGuardar() {
        if (fechaCreacion == null) fechaCreacion = LocalDateTime.now();
        if (estado == null) estado = "VIGENTE";
    }
}

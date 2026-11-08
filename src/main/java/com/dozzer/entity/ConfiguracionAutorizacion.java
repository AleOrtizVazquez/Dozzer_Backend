
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
@Table(name = "configuracion_autorizaciones")
public class ConfiguracionAutorizacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_configuracion")
    private Long idConfiguracion;

    @Column(name = "tipo_operacion", nullable = false, unique = true, length = 30)
    private String tipoOperacion;

    @Column(name = "requiere_autorizacion", nullable = false)
    private Boolean requiereAutorizacion = true;
}

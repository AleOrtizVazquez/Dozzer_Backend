
package com.dozzer.repository;

import com.dozzer.entity.SolicitudBien;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SolicitudBienRepository extends JpaRepository<SolicitudBien, Long> {

    List<SolicitudBien> findByEstadoOrderByFechaSolicitudDesc(String estado);

    List<SolicitudBien> findByUsuarioSolicitanteIdUsuarioOrderByFechaSolicitudDesc(Long idUsuario);

    List<SolicitudBien> findByEstadoAndUsuarioSolicitanteIdUsuarioOrderByFechaSolicitudDesc(
            String estado, Long idUsuario);

    // Consultar solicitudes de una sede
    List<SolicitudBien> findByIdSedeOrderByFechaSolicitudDesc(Long idSede);

    // Consultar solicitudes pendientes de una sede
    List<SolicitudBien> findByEstadoAndIdSedeOrderByFechaSolicitudDesc(
            String estado, Long idSede);
}

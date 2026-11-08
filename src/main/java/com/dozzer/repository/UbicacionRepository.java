package com.dozzer.repository;



import com.dozzer.entity.Ubicacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UbicacionRepository extends JpaRepository<Ubicacion, Long> {

    // Buscar ubicaciones por sede
    List<Ubicacion> findBySede_IdSede(Long idSede);

    // Buscar ubicaciones activas
    List<Ubicacion> findByEstatusTrue();

    // Buscar ubicación por ID y estatus activo
    Optional<Ubicacion> findByIdUbicacionAndEstatusTrue(Long idUbicacion);

    // Buscar ubicación por nombre de área
    Optional<Ubicacion> findByNombreArea(String nombreArea);

    // Consultar ubicaciones ordenadas por nombre de área
    List<Ubicacion> findAllByOrderByNombreAreaAsc();

}

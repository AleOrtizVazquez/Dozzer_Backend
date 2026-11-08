package com.dozzer.repository;


import com.dozzer.entity.Clasificacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClasificacionRepository extends JpaRepository<Clasificacion, Long> {

    // Buscar clasificación por nombre
    Optional<Clasificacion> findByNombre(String nombre);

    // Verificar si existe una clasificación por nombre
    boolean existsByNombre(String nombre);

    // Consultar clasificaciones ordenadas por nombre
    List<Clasificacion> findAllByOrderByNombreAsc();

}


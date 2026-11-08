package com.dozzer.repository;


import com.dozzer.entity.Sede;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SedeRepository extends JpaRepository<Sede, Long> {

    // Buscar sede por nombre
    Optional<Sede> findByNombreSede(String nombreSede);

    // Buscar sedes activas
    List<Sede> findByEstatusTrue();

    // Consultar sedes ordenadas por nombre
    List<Sede> findAllByOrderByNombreSedeAsc();

    // Verificar si existe una sede con determinado nombre
    boolean existsByNombreSede(String nombreSede);

}

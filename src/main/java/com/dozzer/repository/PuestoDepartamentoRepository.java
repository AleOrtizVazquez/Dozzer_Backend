package com.dozzer.repository;


import com.dozzer.entity.PuestoDepartamento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PuestoDepartamentoRepository
        extends JpaRepository<PuestoDepartamento, Long> {

    // Buscar por nombre de puesto
    Optional<PuestoDepartamento> findByNombrePuesto(String nombrePuesto);

    // Buscar puestos por departamento
    List<PuestoDepartamento> findByDepartamento_NombreDepartamento(String nombreDepartamento);

    // Consultar todos ordenados por nombre de puesto
    List<PuestoDepartamento> findAllByOrderByNombrePuestoAsc();

}

package com.dozzer.repository;

import com.dozzer.entity.Levantamiento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LevantamientoRepository extends JpaRepository<Levantamiento, Long> {

    List<Levantamiento> findAllByOrderByIdLevantamientoDesc();

    boolean existsByIdSedeAndEstado(Long idSede, String estado);

    boolean existsByIdSedeAndAnioAndPeriodo(Long idSede, Integer anio, Integer periodo);
}

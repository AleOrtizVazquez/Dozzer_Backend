package com.dozzer.repository;

import com.dozzer.entity.LevantamientoRegistro;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LevantamientoRegistroRepository extends JpaRepository<LevantamientoRegistro, Long> {

    List<LevantamientoRegistro> findByIdLevantamiento(Long idLevantamiento);

    Optional<LevantamientoRegistro> findByIdLevantamientoAndIdBien(Long idLevantamiento, Long idBien);

    long countByIdLevantamiento(Long idLevantamiento);
}

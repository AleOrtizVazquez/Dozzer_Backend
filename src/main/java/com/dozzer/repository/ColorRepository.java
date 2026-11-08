package com.dozzer.repository;



import com.dozzer.entity.Color;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ColorRepository extends JpaRepository<Color, Long> {

    // Buscar color por nombre
    Optional<Color> findByNombreColor(String nombreColor);

    // Verificar si existe un color
    boolean existsByNombreColor(String nombreColor);

    // Listar colores ordenados alfabéticamente
    List<Color> findAllByOrderByNombreColorAsc();

}

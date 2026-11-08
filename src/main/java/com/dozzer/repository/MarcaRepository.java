package com.dozzer.repository;



import com.dozzer.entity.Marca;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarcaRepository extends JpaRepository<Marca, Long> {

    // Buscar marca por nombre
    Optional<Marca> findByNombreMarca(String nombreMarca);

    // Verificar si existe una marca
    boolean existsByNombreMarca(String nombreMarca);

    // Listar marcas ordenadas alfabéticamente
    List<Marca> findAllByOrderByNombreMarcaAsc();

}

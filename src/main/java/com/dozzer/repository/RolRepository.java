package com.dozzer.repository;


import com.dozzer.entity.Rol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RolRepository extends JpaRepository<Rol, Long> {

    // Buscar rol por nombre
    Optional<Rol> findByNombre(String nombre);

    // Verificar si existe un rol
    boolean existsByNombre(String nombre);

}


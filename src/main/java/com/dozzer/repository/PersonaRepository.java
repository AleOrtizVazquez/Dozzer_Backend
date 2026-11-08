package com.dozzer.repository;



import com.dozzer.entity.Persona;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PersonaRepository extends JpaRepository<Persona, Long> {

    // Buscar persona por correo
    Optional<Persona> findByCorreo(String correo);

    // Buscar personas activas
    List<Persona> findByEstatusTrue();

    // Buscar persona por ID y estatus activo
    Optional<Persona> findByIdPersonaAndEstatusTrue(Long idPersona);

    // Buscar personas por sede
    List<Persona> findBySede_IdSedeAndEstatusTrue(Long idSede);

    // Buscar personas por puesto
    List<Persona> findByPuesto_IdPuesto(Long idPuesto);

}

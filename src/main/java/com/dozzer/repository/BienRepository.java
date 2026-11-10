package com.dozzer.repository;



import com.dozzer.entity.Bien;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BienRepository extends JpaRepository<Bien, Long> {

    // Consultar todos los bienes activos con sus catálogos en una sola consulta
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
            "clasificacion", "subcategoria", "marca", "color",
            "ubicacion", "ubicacion.sede", "responsableArea", "usuarioDirecto"
    })
    List<Bien> findByActivoTrueOrderByIdBienDesc();

    // Consultar un bien activo por ID
    Optional<Bien> findByIdBienAndActivoTrue(Long idBien);

    // Buscar un bien por su clave
    Optional<Bien> findByClave(String clave);

    // Verificar si existe una clave
    boolean existsByClave(String clave);

    // Claves que empiezan con el prefijo de la sede (para proponer la siguiente)
    List<Bien> findByClaveStartingWithIgnoreCase(String prefijo);

    // Verificar si una clave pertenece a otro bien
    boolean existsByClaveAndIdBienNot(String clave, Long idBien);

    // Buscar bienes por clasificación
    List<Bien> findByClasificacion_IdClasificacionAndActivoTrue(
            Long idClasificacion
    );

    // Buscar bienes por ubicación
    List<Bien> findByUbicacion_IdUbicacionAndActivoTrue(
            Long idUbicacion
    );

}

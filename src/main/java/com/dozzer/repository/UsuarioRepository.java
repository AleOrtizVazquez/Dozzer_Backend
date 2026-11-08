package com.dozzer.repository;


import com.dozzer.entity.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    // Buscar usuario por correo
    Optional<Usuario> findByCorreo(String correo);

    // Buscar usuario activo por correo
    Optional<Usuario> findByCorreoAndActivoTrue(String correo);

    // Verificar si existe un correo
    boolean existsByCorreo(String correo);

    // Consultar usuarios activos
    List<Usuario> findByActivoTrue();

    // Buscar usuario por ID y estado activo
    Optional<Usuario> findByIdUsuarioAndActivoTrue(Long idUsuario);

    Optional<Usuario> findByCorreoIgnoreCase(String correo);

}

package com.dozzer.controller;

import com.dozzer.entity.Sede;
import com.dozzer.service.UsuarioSedeService;
import com.dozzer.service.impl.UsuarioAdministracionService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {

    @Autowired
    private UsuarioSedeService usuarioSedeService;

    @Autowired
    private UsuarioAdministracionService usuarioAdministracionService;

    // ==========================================
    // ADMINISTRACIÓN DE USUARIOS
    // ==========================================

    // Listar usuarios
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listarUsuarios() {
        return usuarioAdministracionService.listar();
    }

    // Hacer (o dejar de hacer) administrador a un usuario
    @PutMapping("/{idUsuario}/administrador")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> cambiarAdministrador(@PathVariable Long idUsuario, @RequestBody Map<String, Object> datos) {
        return usuarioAdministracionService.cambiarAdministrador(idUsuario, Boolean.TRUE.equals(datos.get("administrador")));
    }

    // Crear usuario
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> crearUsuario(
            @RequestBody Map<String, Object> datos) {

        return usuarioAdministracionService.crear(datos);
    }

    // ==========================================
    // ASIGNACIÓN DE SEDES
    // ==========================================

    @PutMapping("/{idUsuario}/sedes")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Sede> asignarSedes(
            @PathVariable Long idUsuario,
            @RequestBody List<Long> idsSedes) {

        usuarioSedeService.asignarSedes(idUsuario, idsSedes);

        return usuarioSedeService.consultarSedes(idUsuario);
    }

    @GetMapping("/{idUsuario}/sedes")
    @PreAuthorize("hasRole('ADMIN') or @usuarioSedeService.esPropietario(authentication.name, #idUsuario)")
    public List<Sede> consultarSedes(@PathVariable Long idUsuario) {
        return usuarioSedeService.consultarSedes(idUsuario);
    }
}
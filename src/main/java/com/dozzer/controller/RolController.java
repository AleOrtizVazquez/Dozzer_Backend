
package com.dozzer.controller;

import com.dozzer.service.RolService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/roles")
@PreAuthorize("hasRole('ADMIN')")
public class RolController {

    @Autowired
    private RolService rolService;

    @GetMapping
    public List<Map<String,Object>> listarRoles() {
        return rolService.listarRoles();
    }

    @GetMapping("/{idRol}")
    public Map<String,Object> obtenerRol(@PathVariable Long idRol) {
        return rolService.obtenerRol(idRol);
    }

    @PostMapping
    public Map<String,Object> crearRol(@RequestBody Map<String,Object> datos) {
        return rolService.crearRol(datos);
    }

    @PutMapping("/{idRol}")
    public Map<String,Object> actualizarRol(
            @PathVariable Long idRol,
            @RequestBody Map<String,Object> datos) {
        return rolService.actualizarRol(idRol,datos);
    }

    @PutMapping("/{idRol}/permisos")
    public Map<String,Object> asignarPermisos(
            @PathVariable Long idRol,
            @RequestBody List<Long> idsPermisos) {
        return rolService.asignarPermisos(idRol,idsPermisos);
    }
}

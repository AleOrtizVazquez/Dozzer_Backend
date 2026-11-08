
package com.dozzer.controller;

import com.dozzer.service.PermisoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/permisos")
@PreAuthorize("hasRole('ADMIN')")
public class PermisoController {

    @Autowired
    private PermisoService permisoService;

    @GetMapping
    public List<Map<String,Object>> listarPermisos() {
        return permisoService.listarPermisos();
    }

    @GetMapping("/{idPermiso}")
    public Map<String,Object> obtenerPermiso(@PathVariable Long idPermiso) {
        return permisoService.obtenerPermiso(idPermiso);
    }

    @PostMapping
    public Map<String,Object> crearPermiso(@RequestBody Map<String,Object> datos) {
        return permisoService.crearPermiso(datos);
    }

    @PutMapping("/{idPermiso}")
    public Map<String,Object> actualizarPermiso(
            @PathVariable Long idPermiso,
            @RequestBody Map<String,Object> datos) {
        return permisoService.actualizarPermiso(idPermiso,datos);
    }
}

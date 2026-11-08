package com.dozzer.controller;



import com.dozzer.service.ConfiguracionAutorizacionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/configuracion-autorizaciones")
@PreAuthorize("hasRole('ADMIN')")
public class ConfiguracionAutorizacionController {

    @Autowired
    private ConfiguracionAutorizacionService configuracionService;

    @GetMapping
    public List<Map<String, Object>> listarConfiguraciones() {
        return configuracionService.listarConfiguraciones();
    }

    @GetMapping("/{tipoOperacion}")
    public Map<String, Object> obtenerConfiguracion(@PathVariable String tipoOperacion) {
        return configuracionService.obtenerConfiguracion(tipoOperacion);
    }

    @PutMapping("/{tipoOperacion}")
    public Map<String, Object> actualizarConfiguracion(
            @PathVariable String tipoOperacion,
            @RequestBody Map<String, Object> datos) {

        return configuracionService.actualizarConfiguracion(tipoOperacion, datos);
    }
}

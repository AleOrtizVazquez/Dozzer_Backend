package com.dozzer.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Respuesta mínima y pública: sirve para "despertar" el servidor y para el mantenimiento automático (keep-alive).
// No consulta la base ni devuelve información del sistema.
@RestController
public class SaludController {

    @GetMapping("/api/salud")
    public Map<String, Object> salud() {
        // "v" identifica la versión publicada (para saber si Render ya tomó el último cambio)
        return Map.of("ok", true, "v", "2026-11-25-vehiculos-3");
    }
}

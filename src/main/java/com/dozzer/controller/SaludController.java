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
        return Map.of("ok", true);
    }
}

package com.dozzer.controller;

import com.dozzer.service.impl.AvisoCalibracionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

// Prueba de los avisos de calibración: manda ahora el resumen de lo que vence en 30 días o ya venció
@RestController
@RequestMapping("/api/calibraciones")
public class CalibracionController {

    @Autowired
    private AvisoCalibracionService avisos;

    @PostMapping("/avisar-ahora")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> avisarAhora() {
        return avisos.avisar(LocalDate.now(ZoneId.of("America/Mexico_City")), true);
    }
}

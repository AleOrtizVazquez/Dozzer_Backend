package com.dozzer.controller;

import com.dozzer.service.BienService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

// Modo actualización: el administrador coteja área por área (confirmar, alta exprés y baja) sin generar formatos
@RestController
@RequestMapping("/api/actualizacion")
@PreAuthorize("hasRole('ADMIN')")
public class ActualizacionController {

    @Autowired
    private BienService bienService;

    // El bien está en esta área: se mueve aquí si estaba en otra y se actualizan estado, persona y etiqueta
    @PostMapping("/confirmar")
    public Map<String, Object> confirmar(@RequestBody Map<String, Object> datos) {
        return bienService.actualizacionConfirmar(datos);
    }

    // Alta exprés: nombre, área, foto y etiqueta en una sola llamada (la clave se asigna sola)
    @PostMapping(value = "/alta", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> alta(@RequestParam Map<String, String> campos,
                                    @RequestParam(required = false) MultipartFile foto) throws IOException {
        Map<String, Object> datos = new HashMap<>(campos);
        byte[] bytes = foto != null && !foto.isEmpty() ? foto.getBytes() : null;
        return bienService.actualizacionAlta(datos, bytes, foto != null ? foto.getContentType() : null);
    }

    // Baja inmediata de un faltante (sin formato; el motivo queda en el historial del bien)
    @PostMapping("/baja")
    public Map<String, Object> baja(@RequestBody Map<String, Object> datos) {
        return bienService.actualizacionBaja(datos);
    }
}

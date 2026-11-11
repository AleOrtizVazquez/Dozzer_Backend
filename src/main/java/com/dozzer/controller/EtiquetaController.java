package com.dozzer.controller;

import com.dozzer.service.impl.EtiquetaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// Etiquetas QR en blanco
@RestController
@RequestMapping("/api/etiquetas")
@PreAuthorize("isAuthenticated()")
public class EtiquetaController {

    @Autowired private EtiquetaService service;

    @GetMapping("/resumen")
    public Map<String, Object> resumen() {
        return service.resumen();
    }

    @GetMapping("/libres")
    public List<String> libres(@RequestParam(defaultValue = "30") int limite) {
        return service.libres(limite);
    }

    @PostMapping("/lote")
    public Map<String, Object> lote(@RequestBody Map<String, Object> datos) {
        Object c = datos == null ? null : datos.get("cantidad");
        int cantidad;
        try { cantidad = Integer.parseInt(String.valueOf(c)); } catch (Exception e) { cantidad = 0; }
        return service.generarLote(cantidad);
    }

    @GetMapping("/{codigo}")
    public Map<String, Object> consultar(@PathVariable String codigo) {
        return service.consultar(codigo);
    }

    @PostMapping("/{codigo}/asignar")
    public Map<String, Object> asignar(@PathVariable String codigo, @RequestBody Map<String, Object> datos) {
        Object id = datos == null ? null : datos.get("idBien");
        Long idBien = null;
        try { if (id != null) idBien = Long.valueOf(String.valueOf(id)); } catch (NumberFormatException ignored) { }
        boolean reemplazar = datos != null && Boolean.parseBoolean(String.valueOf(datos.get("reemplazar")));
        return service.asignar(codigo, idBien, reemplazar);
    }

    @PostMapping("/{codigo}/liberar")
    public Map<String, Object> liberar(@PathVariable String codigo) {
        return service.liberar(codigo);
    }
}

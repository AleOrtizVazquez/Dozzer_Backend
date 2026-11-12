package com.dozzer.controller;

import com.dozzer.service.impl.RequisicionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// Requisiciones de compra (FA-04-01-02): pedir, autorizar, aprobar o rechazar
@RestController
@RequestMapping("/api/requisiciones")
@PreAuthorize("isAuthenticated()")
public class RequisicionController {

    @Autowired
    private RequisicionService service;

    @GetMapping
    public List<Map<String, Object>> listar() { return service.listar(); }

    // Nombre, cargo, área y sede de quien la va a pedir (para llenar el formato solo)
    @GetMapping("/mis-datos")
    public Map<String, Object> misDatos() { return service.misDatos(); }

    // Listas desplegables del formulario
    @GetMapping("/opciones")
    public Map<String, Object> opciones() { return service.opciones(); }

    @GetMapping("/{id}")
    public Map<String, Object> obtener(@PathVariable Long id) { return service.obtener(id); }

    @PostMapping
    public Map<String, Object> crear(@RequestBody Map<String, Object> datos) { return service.crear(datos); }

    @PostMapping("/{id}/autorizar")
    public Map<String, Object> autorizar(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> datos) { return service.autorizar(id, datos); }

    @PostMapping("/{id}/aprobar")
    public Map<String, Object> aprobar(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> datos) { return service.aprobar(id, datos); }

    @PostMapping("/{id}/rechazar")
    public Map<String, Object> rechazar(@PathVariable Long id, @RequestBody Map<String, Object> datos) { return service.rechazar(id, datos); }

    @PostMapping("/{id}/cancelar")
    public Map<String, Object> cancelar(@PathVariable Long id) { return service.cancelar(id); }
}

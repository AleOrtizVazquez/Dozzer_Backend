package com.dozzer.controller;

import com.dozzer.service.CatalogoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/catalogos")
public class CatalogoController {

 @Autowired
 private CatalogoService catalogoService;

 // LISTAR CATÁLOGO
 @GetMapping("/{catalogo}")
 public List<Map<String,Object>> listar(@PathVariable String catalogo) {
  return catalogoService.listar(catalogo);
 }

 // CREAR REGISTRO
 @PostMapping("/{catalogo}")
 @PreAuthorize("hasRole('ADMIN')")
 public Map<String,Object> crear(
         @PathVariable String catalogo,
         @RequestBody Map<String,Object> datos) {
  return catalogoService.crear(catalogo,datos);
 }

 // ACTUALIZAR REGISTRO
 @PutMapping("/{catalogo}/{id}")
 @PreAuthorize("hasRole('ADMIN')")
 public Map<String,Object> actualizar(
         @PathVariable String catalogo,
         @PathVariable Long id,
         @RequestBody Map<String,Object> datos) {
  return catalogoService.actualizar(catalogo,id,datos);
 }

 // ELIMINAR REGISTRO
 @DeleteMapping("/{catalogo}/{id}")
 @PreAuthorize("hasRole('ADMIN')")
 public Map<String,Object> eliminar(
         @PathVariable String catalogo,
         @PathVariable Long id) {
  return catalogoService.eliminar(catalogo,id);
 }
}

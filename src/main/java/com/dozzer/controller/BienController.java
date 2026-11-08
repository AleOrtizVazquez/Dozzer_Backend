
package com.dozzer.controller;

import com.dozzer.service.BienService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bienes")
public class BienController {

 @Autowired
 private BienService bienService;

 // Consultar bienes
 @GetMapping
 @PreAuthorize("hasRole('ADMIN') or hasAuthority('BIENES_CONSULTAR')")
 public List<Map<String, Object>> listarBienes() {
  return bienService.listarBienes();
 }

 // Consultar un bien
 @GetMapping("/{id}")
 @PreAuthorize("hasRole('ADMIN') or hasAuthority('BIENES_CONSULTAR')")
 public Map<String, Object> obtenerBien(@PathVariable Long id) {
  return bienService.obtenerBien(id);
 }

 // Crear bien
 @PostMapping
 @ResponseStatus(HttpStatus.CREATED)
 @PreAuthorize("hasRole('ADMIN') or hasAuthority('BIENES_CREAR')")
 public Map<String, Object> crearBien(
         @RequestBody Map<String, Object> datos) {

  return bienService.crearBien(datos);
 }

 // Modificar bien
 @PutMapping("/{id}")
 @PreAuthorize("hasRole('ADMIN') or hasAuthority('BIENES_MODIFICAR')")
 public Map<String, Object> actualizarBien(
         @PathVariable Long id,
         @RequestBody Map<String, Object> datos) {

  return bienService.actualizarBien(id, datos);
 }

 // Dar de baja bien
 @DeleteMapping("/{id}")
 @PreAuthorize("hasRole('ADMIN') or hasAuthority('BIENES_ELIMINAR')")
 public Map<String, Object> eliminarBien(@PathVariable Long id) {
  return bienService.eliminarBien(id);
 }

 // Importar bienes desde Excel
 @PostMapping("/importar")
 @ResponseStatus(HttpStatus.OK)
 @PreAuthorize("hasRole('ADMIN')")
 public Map<String, Object> importarBienes(
         @RequestBody Map<String, Object> request) {

  Object filas = request.get("rows");

  if (!(filas instanceof List<?> lista)) {
   throw new IllegalArgumentException(
           "El archivo no contiene una lista de registros válida");
  }

  List<Map<String, Object>> registros = lista.stream()
          .filter(Map.class::isInstance)
          .map(fila -> (Map<String, Object>) fila)
          .toList();

  if (registros.size() != lista.size()) {
   throw new IllegalArgumentException(
           "Una o más filas del archivo tienen un formato inválido");
  }

  return bienService.importarBienes(registros);
 }
}

package com.dozzer.controller;

import com.dozzer.service.impl.LevantamientoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Inventario anual
@RestController
@RequestMapping("/api")
@PreAuthorize("isAuthenticated()")
public class LevantamientoController {

    @Autowired
    private LevantamientoService service;

    @GetMapping("/levantamientos")
    public List<Map<String, Object>> listar() {
        return service.listar();
    }

    @GetMapping("/levantamientos/{id}")
    public Map<String, Object> obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @GetMapping("/levantamientos/candidatos")
    public List<Map<String, Object>> candidatos(@RequestParam Long idSede) {
        return service.candidatos(idSede);
    }

    @PostMapping("/levantamientos")
    public Map<String, Object> crear(@RequestBody Map<String, Object> datos) {
        return service.crear(datos);
    }

    @GetMapping("/levantamientos/{id}/bienes")
    public List<Map<String, Object>> bienes(@PathVariable Long id) {
        return service.bienes(id);
    }

    @PostMapping(value = "/levantamientos/{id}/registros", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> registrar(@PathVariable Long id,
                                         @RequestParam Long idBien,
                                         @RequestParam String respuesta,
                                         @RequestParam(required = false) Long idUbicacion,
                                         @RequestParam(required = false) MultipartFile foto) {
        try {
            byte[] bytes = foto != null && !foto.isEmpty() ? foto.getBytes() : null;
            return service.registrar(id, idBien, respuesta, idUbicacion, bytes, foto != null ? foto.getContentType() : null);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo leer la foto");
        }
    }

    // Quién ya firmó su resguardo en este levantamiento
    @GetMapping("/levantamientos/{id}/personas-estado")
    public Map<String, Object> estadoPersonas(@PathVariable Long id) {
        return service.estadoPersonas(id);
    }

    // Terminar con una persona: pendientes, conformidad y firmas
    @PostMapping("/levantamientos/{id}/personas/{idPersona}/terminar")
    public Map<String, Object> terminarPersona(@PathVariable Long id, @PathVariable Long idPersona, @RequestBody Map<String, Object> datos) {
        return service.terminarPersona(id, idPersona, datos == null ? Map.of() : datos);
    }

    // Palomita "No encontrado"
    @PostMapping("/levantamientos/{id}/no-encontrados")
    public Map<String, Object> noEncontrado(@PathVariable Long id, @RequestBody Map<String, Object> datos) {
        return service.marcarNoEncontrado(id, largo(datos.get("idBien")));
    }

    // Áreas, personas y catálogos de la sede
    @GetMapping("/levantamientos/{id}/opciones")
    public Map<String, Object> opciones(@PathVariable Long id) {
        return service.opciones(id);
    }

    // Buscar un bien en todo el inventario (todas las sedes)
    @GetMapping("/levantamientos/{id}/buscar")
    public List<Map<String, Object>> buscar(@PathVariable Long id, @RequestParam String q) {
        return service.buscarGeneral(id, q);
    }

    // Traer a esta sede un bien que el sistema tiene en otra
    @PostMapping("/levantamientos/{id}/traer")
    public Map<String, Object> traer(@PathVariable Long id, @RequestBody Map<String, Object> datos) {
        return service.traer(id, largo(datos.get("idBien")), largo(datos.get("idUbicacion")), largo(datos.get("idPersona")));
    }

    // Bien nuevo encontrado durante el levantamiento (entra directo)
    @PostMapping("/levantamientos/{id}/bienes-nuevos")
    public Map<String, Object> nuevoBien(@PathVariable Long id, @RequestBody Map<String, Object> datos) {
        return service.nuevoBien(id, datos == null ? Map.of() : datos);
    }

    private static Long largo(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        try { return Long.valueOf(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dato numérico no válido"); }
    }

    @DeleteMapping("/levantamientos/{id}")
    public Map<String, Object> eliminar(@PathVariable Long id) {
        return service.eliminar(id);
    }

    @PostMapping("/levantamientos/{id}/cerrar")
    public Map<String, Object> cerrar(@PathVariable Long id) {
        return service.cerrar(id);
    }

    @GetMapping("/bienes/{idBien}/foto")
    public ResponseEntity<byte[]> foto(@PathVariable Long idBien) {
        Map<String, Object> f = service.foto(idBien);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(String.valueOf(f.get("tipo"))))
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePrivate())
                .body((byte[]) f.get("bytes"));
    }
}

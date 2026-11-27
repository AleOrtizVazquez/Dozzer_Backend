package com.dozzer.controller;

import com.dozzer.service.impl.VehiculoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

// Control vehicular. Los permisos finos (administrador de flota, responsable, QR) se validan en VehiculoService.
@RestController
@RequestMapping("/api/vehiculos")
@PreAuthorize("isAuthenticated()")
public class VehiculoController {

    @Autowired
    private VehiculoService service;

    // ---- Para todos ----
    @GetMapping("/perfil")
    public Map<String, Object> perfil() { return service.perfil(); }

    @GetMapping("/podio")
    public Map<String, Object> podio() { return service.podio(); }

    @PostMapping("/mi-persona")
    public Map<String, Object> ligarMiPersona(@RequestBody Map<String, Object> d) {
        Object p = d == null ? null : d.get("idPersona");
        boolean reemplazar = d != null && Boolean.parseBoolean(String.valueOf(d.get("reemplazar")));
        return service.ligarMiPersona(p == null || String.valueOf(p).isBlank() ? null : Long.valueOf(String.valueOf(p)), reemplazar);
    }

    @GetMapping("/qr/{codigo}")
    public Map<String, Object> porQr(@PathVariable String codigo) { return service.porQr(codigo); }

    @GetMapping("/{id:\\d+}")
    public Map<String, Object> ficha(@PathVariable Long id) { return service.ficha(id); }

    @GetMapping("/{id:\\d+}/pitstop")
    public Map<String, Object> pitstop(@PathVariable Long id, @RequestParam(required = false) String qr) { return service.pitstop(id, qr); }

    @PostMapping(value = "/{id:\\d+}/pitstop", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> registrarPitstop(@PathVariable Long id,
                                                @RequestParam Integer km,
                                                @RequestParam String datos,
                                                @RequestParam(required = false) Integer segundos,
                                                @RequestParam(required = false) String qr,
                                                @RequestParam(required = false) MultipartFile foto,
                                                @RequestParam(required = false) MultipartFile fotoTablero) throws IOException {
        if (datos.length() > 20_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos demasiado grandes");
        byte[] bytes = foto != null && !foto.isEmpty() ? foto.getBytes() : null;
        byte[] tablero = fotoTablero != null && !fotoTablero.isEmpty() ? fotoTablero.getBytes() : null;
        return service.registrarRevision(id, qr, km, segundos, datos, bytes, foto != null ? foto.getContentType() : null,
                tablero, fotoTablero != null ? fotoTablero.getContentType() : null);
    }

    // ---- Administrador de flota ----
    @GetMapping
    public List<Map<String, Object>> listar() { return service.listar(); }

    @GetMapping("/decisiones")
    public Map<String, Object> decisiones() { return service.decisiones(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crear(@RequestBody Map<String, Object> datos) { return service.guardar(null, datos); }

    @PutMapping("/{id:\\d+}")
    public Map<String, Object> actualizar(@PathVariable Long id, @RequestBody Map<String, Object> datos) { return service.guardar(id, datos); }

    @DeleteMapping("/{id:\\d+}")
    public Map<String, Object> baja(@PathVariable Long id, @RequestParam(required = false) String motivo) { return service.darDeBaja(id, motivo); }

    @PostMapping("/importar")
    public Map<String, Object> importar(@RequestBody Map<String, Object> cuerpo) {
        Object filas = cuerpo == null ? null : cuerpo.get("filas");
        if (!(filas instanceof List<?> l)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo no trae vehículos");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lista = l.stream().filter(Map.class::isInstance).map(x -> (Map<String, Object>) x).toList();
        return service.importar(lista);
    }

    @PutMapping("/{id:\\d+}/responsable")
    public Map<String, Object> responsable(@PathVariable Long id, @RequestBody Map<String, Object> datos) {
        Object p = datos == null ? null : datos.get("idPersona");
        Long idPersona = p == null || String.valueOf(p).isBlank() ? null : Long.valueOf(String.valueOf(p));
        return service.asignarResponsable(id, idPersona);
    }

    @GetMapping("/responsables/sugerencias")
    public List<Map<String, Object>> sugerenciasResponsables() { return service.sugerenciasResponsables(); }

    @PostMapping("/responsables/ligar")
    public Map<String, Object> ligarResponsables(@RequestBody Map<String, Object> cuerpo) {
        Object pares = cuerpo == null ? null : cuerpo.get("pares");
        if (!(pares instanceof List<?> l)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay nada que ligar");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lista = l.stream().filter(Map.class::isInstance).map(x -> (Map<String, Object>) x).toList();
        return service.ligarResponsables(lista);
    }

    @PostMapping("/{id:\\d+}/poliza")
    public Map<String, Object> poliza(@PathVariable Long id, @RequestBody Map<String, Object> d) { return service.renovarPoliza(id, d); }

    @PostMapping("/{id:\\d+}/verificacion")
    public Map<String, Object> verificacion(@PathVariable Long id, @RequestBody Map<String, Object> d) { return service.verificacionHecha(id, d); }

    @PostMapping("/{id:\\d+}/tenencia")
    public Map<String, Object> tenencia(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> d) { return service.tenenciaPagada(id, d); }

    @PostMapping("/{id:\\d+}/servicio")
    public Map<String, Object> servicio(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> d) { return service.servicioHecho(id, d); }

    @PostMapping("/{id:\\d+}/taller")
    public Map<String, Object> taller(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> d) { return service.mandarTaller(id, d); }

    @PostMapping("/{id:\\d+}/posponer")
    public Map<String, Object> posponer(@PathVariable Long id, @RequestBody Map<String, Object> d) { return service.posponer(id, d); }

    @PostMapping("/{id:\\d+}/recordar")
    public Map<String, Object> recordar(@PathVariable Long id) { return service.recordar(id); }

    @PostMapping("/{id:\\d+}/acceso")
    public Map<String, Object> acceso(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> d) { return service.darAcceso(id, d); }

    @PostMapping("/pendientes/{idPendiente:\\d+}/cerrar")
    public Map<String, Object> cerrarPendiente(@PathVariable Long idPendiente, @RequestBody(required = false) Map<String, Object> d) { return service.cerrarPendiente(idPendiente, d); }

    @PostMapping("/revisiones/{idRevision:\\d+}/calificar")
    public Map<String, Object> calificar(@PathVariable Long idRevision, @RequestBody Map<String, Object> d) {
        return service.calificarRevision(idRevision, d == null ? null : String.valueOf(d.get("calificacion")));
    }

    // ---- Administrador general: quién administra la flota ----
    @GetMapping("/administradores")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> administradores() { return service.administradoresFlota(); }

    @PutMapping("/administradores/{idUsuario:\\d+}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> cambiarAdministrador(@PathVariable Long idUsuario, @RequestBody Map<String, Object> d) {
        return service.cambiarAdministradorFlota(idUsuario, d != null && Boolean.TRUE.equals(d.get("activo")));
    }
}

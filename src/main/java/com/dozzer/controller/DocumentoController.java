package com.dozzer.controller;

import com.dozzer.service.impl.DocumentoFormatoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// Formatos de Alta/Baja guardados (el PDF se arma en el navegador con estos datos)
@RestController
@RequestMapping("/api/documentos")
@PreAuthorize("isAuthenticated()")
public class DocumentoController {

    @Autowired
    private DocumentoFormatoService documentoFormatoService;

    // Cada quien ve solo lo que le corresponde
    @GetMapping
    public List<Map<String, Object>> listar(@RequestParam(required = false) String tipo,
                                            @RequestParam(required = false) Long idSolicitud,
                                            @RequestParam(required = false) Long idBien) {
        return documentoFormatoService.listar(tipo, idSolicitud, idBien);
    }

    @GetMapping("/conteo")
    public Map<String, Object> conteo() {
        return documentoFormatoService.conteo();
    }

    @GetMapping("/{idDocumento:\\d+}")
    public Map<String, Object> obtener(@PathVariable Long idDocumento) {
        return documentoFormatoService.obtener(idDocumento);
    }
}

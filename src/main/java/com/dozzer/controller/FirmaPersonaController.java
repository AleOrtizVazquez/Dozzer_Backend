package com.dozzer.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

// Firma guardada de cada persona del catálogo (tenga o no usuario). Se captura la primera vez que firma
// en un documento y después se le propone en sus siguientes documentos.
@RestController
@RequestMapping("/api/personas")
@PreAuthorize("isAuthenticated()")
public class FirmaPersonaController {

    private static final int MAXIMO = 200_000;

    @Autowired private JdbcTemplate jdbc;

    // Firmas de varias personas a la vez: ?ids=1,2,3 → { "1": "data:image/png...", ... } (solo las que tienen)
    @GetMapping("/firmas")
    public Map<String, Object> firmas(@RequestParam(defaultValue = "") String ids) {
        List<Long> lista = new ArrayList<>();
        for (String p : ids.split(",")) {
            try { if (!p.isBlank()) lista.add(Long.valueOf(p.trim())); } catch (NumberFormatException ignored) { }
            if (lista.size() >= 60) break;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        if (lista.isEmpty()) return r;
        String marcas = String.join(",", Collections.nCopies(lista.size(), "?"));
        jdbc.queryForList("select id_persona, firma from cat_personas where firma is not null and id_persona in (" + marcas + ")", lista.toArray())
            .forEach(m -> r.put(String.valueOf(m.get("id_persona")), m.get("firma")));
        return r;
    }

    @PutMapping("/{id}/firma")
    public Map<String, Object> guardar(@PathVariable Long id, @RequestBody Map<String, Object> datos, Authentication auth) {
        String firma = datos == null || datos.get("firma") == null ? "" : String.valueOf(datos.get("firma"));
        if (!firma.startsWith("data:image/png;base64,") || firma.length() > MAXIMO || !firma.substring(22).matches("[A-Za-z0-9+/=]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La firma no es válida; vuelve a hacerla");
        }
        int n = jdbc.update("update cat_personas set firma = ?, firma_actualizada = ?, firma_registrada_por = ? where id_persona = ?",
                firma, Timestamp.valueOf(LocalDateTime.now()), auth != null ? auth.getName() : null, id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Persona no encontrada");
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}/firma")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> borrar(@PathVariable Long id) {
        jdbc.update("update cat_personas set firma = null, firma_actualizada = null, firma_registrada_por = null where id_persona = ?", id);
        return Map.of("ok", true);
    }
}

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Firma guardada del usuario que tiene la sesión: la usa en cualquier celular o computadora
@RestController
@RequestMapping("/api/usuarios/mi-firma")
@PreAuthorize("isAuthenticated()")
public class MiFirmaController {

    private static final int MAXIMO = 200_000;

    @Autowired private JdbcTemplate jdbc;

    @GetMapping
    public Map<String, Object> obtener(Authentication auth) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "select firma, firma_actualizada from usuarios where lower(correo) = lower(?)", correo(auth));
        Map<String, Object> m = new HashMap<>();
        m.put("firma", r.isEmpty() ? null : r.get(0).get("firma"));
        m.put("actualizada", r.isEmpty() ? null : r.get(0).get("firma_actualizada"));
        return m;
    }

    @PutMapping
    public Map<String, Object> guardar(Authentication auth, @RequestBody Map<String, Object> datos) {
        String firma = datos == null || datos.get("firma") == null ? "" : String.valueOf(datos.get("firma"));
        if (!firma.startsWith("data:image/png;base64,") || firma.length() > MAXIMO || !firma.substring(22).matches("[A-Za-z0-9+/=]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La firma no es válida; vuelve a hacerla");
        }
        int n = jdbc.update("update usuarios set firma = ?, firma_actualizada = ? where lower(correo) = lower(?)",
                firma, Timestamp.valueOf(LocalDateTime.now()), correo(auth));
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado");
        return Map.of("ok", true);
    }

    @DeleteMapping
    public Map<String, Object> borrar(Authentication auth) {
        jdbc.update("update usuarios set firma = null, firma_actualizada = null where lower(correo) = lower(?)", correo(auth));
        return Map.of("ok", true);
    }

    private String correo(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin sesión");
        return auth.getName();
    }
}

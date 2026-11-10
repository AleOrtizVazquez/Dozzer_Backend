package com.dozzer.controller;

import com.dozzer.entity.Usuario;
import com.dozzer.repository.UsuarioRepository;
import com.dozzer.service.impl.PushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

// Notificaciones al celular: cada usuario registra aquí su navegador/celular
@RestController
@RequestMapping("/api/push")
@PreAuthorize("isAuthenticated()")
public class PushController {

    @Autowired private PushService push;
    @Autowired private UsuarioRepository usuarioRepository;

    @GetMapping("/clave-publica")
    public Map<String, Object> clavePublica(Authentication auth) {
        Usuario u = usuario(auth);
        return Map.of("publicKey", push.clavePublica(), "dispositivos", push.contar(u.getIdUsuario()));
    }

    // {endpoint, keys: {p256dh, auth}} tal como lo da el navegador
    @PostMapping("/suscripciones")
    public Map<String, Object> suscribir(@RequestBody Map<String, Object> datos, Authentication auth,
                                         @RequestHeader(value = "User-Agent", required = false) String agente) {
        Usuario u = usuario(auth);
        Object keys = datos == null ? null : datos.get("keys");
        if (!(keys instanceof Map<?, ?> k)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos de notificación incompletos");
        push.suscribir(u.getIdUsuario(), texto(datos.get("endpoint")), texto(k.get("p256dh")), texto(k.get("auth")), agente);
        return Map.of("success", true);
    }

    @PostMapping("/suscripciones/quitar")
    public Map<String, Object> quitar(@RequestBody Map<String, Object> datos, Authentication auth) {
        push.quitar(usuario(auth).getIdUsuario(), datos == null ? null : texto(datos.get("endpoint")));
        return Map.of("success", true);
    }

    // Aviso de prueba a los celulares del propio usuario
    @PostMapping("/prueba")
    public Map<String, Object> prueba(Authentication auth) {
        Usuario u = usuario(auth);
        int n = push.enviarAhora(Set.of(u.getIdUsuario()), "DOZZER · avisos activados 🔔",
                "Así te llegarán las autorizaciones y respuestas.", "/", "prueba");
        return Map.of("success", n > 0, "enviados", n);
    }

    private Usuario usuario(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        return usuarioRepository.findByCorreoIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }

    private static String texto(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}

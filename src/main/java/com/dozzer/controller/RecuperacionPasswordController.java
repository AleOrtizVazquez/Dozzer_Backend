package com.dozzer.controller;

import com.dozzer.entity.Sede;
import com.dozzer.entity.Usuario;
import com.dozzer.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

// El administrador puede asignar una contraseña temporal a cualquier usuario;
// el usuario debe cambiarla al entrar.
@RestController
@RequestMapping("/api/usuarios/recuperaciones")
@PreAuthorize("hasRole('ADMIN')")
public class RecuperacionPasswordController {

    // Sin caracteres que se confunden al dictarlos (0/O, 1/l/I)
    private static final String LETRAS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";
    private static final String NUMEROS = "23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Asigna una contraseña temporal (sirve para cualquier usuario, haya pedido o no la recuperación)
    @PostMapping("/restablecer")
    @Transactional
    public Map<String, Object> restablecer(@RequestBody Map<String, String> datos, Authentication authentication) {
        String correo = datos == null || datos.get("correo") == null ? "" : datos.get("correo").trim();
        if (correo.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el correo del usuario");
        }
        Usuario usuario = usuarioRepository.findByCorreoIgnoreCase(correo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe un usuario con el correo " + correo));
        if (Boolean.FALSE.equals(usuario.getActivo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El usuario está inactivo");
        }
        if (authentication != null && usuario.getCorreo().equalsIgnoreCase(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Para tu propia cuenta usa la opción Cambiar contraseña del menú");
        }

        String temporal = generarTemporal();
        usuario.setPassword(passwordEncoder.encode(temporal));
        usuario.setDebeCambiarPassword(true);
        usuarioRepository.save(usuario);

        Map<String, Object> respuesta = convertir(usuario);
        respuesta.put("passwordTemporal", temporal);
        return respuesta;
    }

    private Map<String, Object> convertir(Usuario u) {
        Map<String, Object> m = new HashMap<>();
        m.put("idUsuario", u.getIdUsuario());
        m.put("correo", u.getCorreo());
        m.put("nombre", u.getPersona() != null ? u.getPersona().getNombreCompleto() : null);
        m.put("sedes", u.getSedes().stream().map(Sede::getNombreSede).filter(Objects::nonNull).sorted().collect(Collectors.joining(", ")));
        return m;
    }

    // Formato fácil de dictar: Dz + 4 letras + 4 números (cumple: 8+ caracteres, letras y números)
    private String generarTemporal() {
        StringBuilder sb = new StringBuilder("Dz");
        for (int i = 0; i < 4; i++) sb.append(LETRAS.charAt(RANDOM.nextInt(LETRAS.length())));
        for (int i = 0; i < 4; i++) sb.append(NUMEROS.charAt(RANDOM.nextInt(NUMEROS.length())));
        return sb.toString();
    }
}

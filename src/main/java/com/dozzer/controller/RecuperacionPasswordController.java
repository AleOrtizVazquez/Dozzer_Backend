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

    // Genera una contraseña temporal DISTINTA para cada empleado que todavía no ha cambiado la suya.
    // Las contraseñas solo se regresan en esta respuesta (el navegador las descarga en un archivo);
    // en la base solo queda su versión cifrada. Volver a generarlas invalida las anteriores.
    @PostMapping("/temporales-pendientes")
    @Transactional
    public Map<String, Object> temporalesPendientes(Authentication authentication) {
        Usuario yo = authentication == null ? null : usuarioRepository.findByCorreoIgnoreCase(authentication.getName()).orElse(null);
        if (yo == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        Set<Long> misSedes = yo.getSedes().stream().map(Sede::getIdSede).collect(Collectors.toSet());

        List<Map<String, Object>> lista = new ArrayList<>();
        for (Usuario u : usuarioRepository.findAll()) {
            if (Boolean.FALSE.equals(u.getActivo()) || !Boolean.TRUE.equals(u.getDebeCambiarPassword())) continue;
            if (u.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getNombre()))) continue;
            // Un administrador de sede solo genera las de su sede
            if (!misSedes.isEmpty() && u.getSedes().stream().noneMatch(s -> misSedes.contains(s.getIdSede()))) continue;
            String temporal = generarTemporal();
            u.setPassword(passwordEncoder.encode(temporal));
            usuarioRepository.save(u);
            Map<String, Object> m = convertir(u);
            m.put("numeroEmpleado", u.getPersona() != null ? u.getPersona().getNumeroEmpleado() : null);
            m.put("passwordTemporal", temporal);
            lista.add(m);
        }
        lista.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("sedes")))
                .thenComparing(m -> String.valueOf(m.get("nombre"))));
        return Map.of("total", lista.size(), "usuarios", lista);
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

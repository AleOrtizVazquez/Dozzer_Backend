package com.dozzer.service.impl;


import com.dozzer.entity.Rol;
import com.dozzer.entity.Sede;
import com.dozzer.entity.Usuario;
import com.dozzer.repository.RolRepository;
import com.dozzer.repository.SedeRepository;
import com.dozzer.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
@Transactional
public class UsuarioAdministracionService {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private RolRepository rolRepository;

    @Autowired
    private SedeRepository sedeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Cuenta principal del sistema: nadie le quita el rol de administrador
    @org.springframework.beans.factory.annotation.Value("${app.admin.email:admin@dozzer.com}")
    private String correoPrincipal;

    public Map<String, Object> cambiarAdministrador(Long idUsuario, boolean administrador) {
        Usuario u = usuarioRepository.findById(idUsuario)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        String yo = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
        if (u.getCorreo().equalsIgnoreCase(correoPrincipal.trim()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cuenta principal siempre es administradora");
        if (!administrador && u.getCorreo().equalsIgnoreCase(yo))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No puedes quitarte a ti mismo el rol de administrador");
        Rol rol = rolRepository.findAll().stream()
                .filter(r -> (administrador ? "ADMIN" : "EMPLEADO").equalsIgnoreCase(r.getNombre()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No existe el rol"));
        u.setRoles(new HashSet<>(Set.of(rol)));
        // Administrador = todas las sedes (sin sede asignada). Si vuelve a ser empleado, recupera la sede de su ficha.
        if (administrador) {
            u.getSedes().clear();
        } else if (u.getSedes().isEmpty() && u.getPersona() != null && u.getPersona().getSede() != null) {
            u.getSedes().add(u.getPersona().getSede());
        }
        return mapear(usuarioRepository.save(u));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar() {
        return usuarioRepository.findAll()
                .stream()
                .map(this::mapear)
                .toList();
    }

    public Map<String, Object> crear(Map<String, Object> datos) {

        if (datos == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debes proporcionar los datos del usuario"
            );
        }

        String correo = texto(datos.get("correo"))
                .toLowerCase(Locale.ROOT);

        String password = texto(datos.get("password"));

        Long idRol = numero(datos.get("idRol"));

        List<Long> idsSedes = listaIds(datos.get("idsSedes"));

        if (!correo.matches(
                "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Ingresa un correo electrónico válido"
            );
        }

        if (password.length() < 8) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La contraseña debe tener al menos 8 caracteres"
            );
        }

        if (idRol == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debes seleccionar un rol"
            );
        }

        if (idsSedes.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Asigna al menos una sede al empleado"
            );
        }

        if (usuarioRepository.findByCorreoIgnoreCase(correo).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ya existe un usuario con ese correo"
            );
        }

        Rol rol = rolRepository.findById(idRol)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "El rol seleccionado no existe"
                ));

        if ("ADMIN".equalsIgnoreCase(rol.getNombre())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "No se pueden crear administradores desde este formulario"
            );
        }

        List<Long> idsUnicos = idsSedes.stream()
                .distinct()
                .toList();

        List<Sede> sedes = sedeRepository.findAllById(idsUnicos)
                .stream()
                .filter(s -> Boolean.TRUE.equals(s.getEstatus()))
                .toList();

        if (sedes.size() != idsUnicos.size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Una o más sedes no existen o están inactivas"
            );
        }

        Usuario usuario = new Usuario();

        usuario.setCorreo(correo);
        usuario.setPassword(passwordEncoder.encode(password));
        usuario.setActivo(true);
        // La contraseña la define el administrador: el empleado debe cambiarla al entrar
        usuario.setDebeCambiarPassword(true);
        usuario.setRoles(new HashSet<>(Set.of(rol)));
        usuario.setSedes(new HashSet<>(sedes));

        Usuario guardado = usuarioRepository.save(usuario);

        return mapear(guardado);
    }

    private Map<String, Object> mapear(Usuario usuario) {

        Map<String, Object> resultado = new LinkedHashMap<>();

        resultado.put("idUsuario", usuario.getIdUsuario());
        resultado.put("correo", usuario.getCorreo());
        resultado.put("activo", usuario.getActivo());
        resultado.put("nombre", usuario.getPersona() != null ? usuario.getPersona().getNombreCompleto() : null);
        resultado.put("administrador", usuario.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getNombre())));
        resultado.put("principal", correoPrincipal != null && usuario.getCorreo().equalsIgnoreCase(correoPrincipal.trim()));

        resultado.put(
                "roles",
                usuario.getRoles()
                        .stream()
                        .map(Rol::getNombre)
                        .sorted()
                        .toList()
        );

        resultado.put(
                "sedes",
                usuario.getSedes().stream().map(s -> {
                    Map<String, Object> sede = new LinkedHashMap<>();
                    sede.put("idSede", s.getIdSede());
                    sede.put("nombreSede", s.getNombreSede());
                    return sede;
                }).toList()
        );

        return resultado;
    }

    private String texto(Object valor) {
        return valor == null ? "" : valor.toString().trim();
    }

    private Long numero(Object valor) {

        if (valor == null || valor.toString().isBlank()) {
            return null;
        }

        try {
            return Long.valueOf(valor.toString());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El identificador del rol no es válido"
            );
        }
    }

    private List<Long> listaIds(Object valor) {

        if (!(valor instanceof List<?> lista)) {
            return List.of();
        }

        List<Long> ids = new ArrayList<>();

        for (Object item : lista) {
            Long id = numero(item);

            if (id != null) {
                ids.add(id);
            }
        }

        return ids;
    }
}

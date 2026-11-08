
package com.dozzer.service.impl;

import com.dozzer.entity.Rol;
import com.dozzer.entity.Permiso;
import com.dozzer.repository.RolRepository;
import com.dozzer.repository.PermisoRepository;
import com.dozzer.service.RolService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
@Transactional
public class RolServiceImpl implements RolService {

    @Autowired
    private RolRepository rolRepository;

    @Autowired
    private PermisoRepository permisoRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Map<String,Object>> listarRoles() {
        return rolRepository.findAll().stream()
                .map(this::convertirAMap)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String,Object> obtenerRol(Long idRol) {
        return convertirAMap(buscarRol(idRol));
    }

    @Override
    public Map<String,Object> crearRol(Map<String,Object> datos) {
        String nombre = obtenerNombre(datos);

        if (nombre.equals("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El rol ADMIN ya está reservado");
        }

        if (rolRepository.findByNombre(nombre).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"El rol ya existe");
        }

        Rol rol = new Rol();
        rol.setNombre(nombre);

        return convertirAMap(rolRepository.save(rol));
    }

    @Override
    public Map<String,Object> actualizarRol(Long idRol,Map<String,Object> datos) {
        Rol rol = buscarRol(idRol);

        if (rol.getNombre().equalsIgnoreCase("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No se puede modificar el nombre del rol ADMIN");
        }

        String nombre = obtenerNombre(datos);

        Optional<Rol> existente = rolRepository.findByNombre(nombre);

        if (existente.isPresent() && !existente.get().getId().equals(idRol)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"El rol ya existe");
        }

        rol.setNombre(nombre);

        return convertirAMap(rolRepository.save(rol));
    }

    @Override
    public Map<String,Object> asignarPermisos(Long idRol,List<Long> idsPermisos) {
        Rol rol = buscarRol(idRol);

        if (idsPermisos == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Debes proporcionar una lista de permisos");
        }

        List<Long> idsUnicos = idsPermisos.stream().distinct().toList();
        List<Permiso> permisos = permisoRepository.findAllById(idsUnicos);

        if (permisos.size() != idsUnicos.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Uno o más permisos no existen");
        }

        rol.getPermisos().clear();
        rol.getPermisos().addAll(permisos);

        return convertirAMap(rolRepository.save(rol));
    }

    private Rol buscarRol(Long idRol) {
        return rolRepository.findById(idRol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Rol no encontrado"));
    }

    private String obtenerNombre(Map<String,Object> datos) {
        if (datos == null || datos.get("nombre") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El nombre del rol es obligatorio");
        }

        String nombre = datos.get("nombre").toString().trim().toUpperCase();

        if (nombre.isBlank() || nombre.length() > 40 || !nombre.matches("[A-Z0-9_]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El nombre debe contener únicamente letras, números y guion bajo");
        }

        return nombre;
    }

    private Map<String,Object> convertirAMap(Rol rol) {
        Map<String,Object> resultado = new HashMap<>();
        resultado.put("id",rol.getId());
        resultado.put("nombre",rol.getNombre());
        resultado.put("permisos",rol.getPermisos().stream().map(p -> {
            Map<String,Object> permiso = new HashMap<>();
            permiso.put("idPermiso",p.getIdPermiso());
            permiso.put("codigo",p.getCodigo());
            permiso.put("nombre",p.getNombre());
            permiso.put("descripcion",p.getDescripcion());
            return permiso;
        }).toList());
        return resultado;
    }
}

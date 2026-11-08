
package com.dozzer.service.impl;

import com.dozzer.entity.Permiso;
import com.dozzer.repository.PermisoRepository;
import com.dozzer.service.PermisoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
@Transactional
public class PermisoServiceImpl implements PermisoService {

    @Autowired
    private PermisoRepository permisoRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Map<String,Object>> listarPermisos() {
        return permisoRepository.findAll().stream()
                .map(this::convertirAMap)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String,Object> obtenerPermiso(Long idPermiso) {
        return convertirAMap(buscarPermiso(idPermiso));
    }

    @Override
    public Map<String,Object> crearPermiso(Map<String,Object> datos) {
        String codigo = obtenerTexto(datos,"codigo").toUpperCase();
        String nombre = obtenerTexto(datos,"nombre");
        String descripcion = obtenerTexto(datos,"descripcion");

        validar(codigo,nombre);

        if (permisoRepository.existsByCodigo(codigo)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"El código del permiso ya existe");
        }

        Permiso permiso = new Permiso();
        permiso.setCodigo(codigo);
        permiso.setNombre(nombre);
        permiso.setDescripcion(descripcion);

        return convertirAMap(permisoRepository.save(permiso));
    }

    @Override
    public Map<String,Object> actualizarPermiso(Long idPermiso,Map<String,Object> datos) {
        Permiso permiso = buscarPermiso(idPermiso);

        String codigo = obtenerTexto(datos,"codigo").toUpperCase();
        String nombre = obtenerTexto(datos,"nombre");
        String descripcion = obtenerTexto(datos,"descripcion");

        validar(codigo,nombre);

        Optional<Permiso> existente = permisoRepository.findByCodigo(codigo);

        if (existente.isPresent() && !existente.get().getIdPermiso().equals(idPermiso)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"El código del permiso ya existe");
        }

        permiso.setCodigo(codigo);
        permiso.setNombre(nombre);
        permiso.setDescripcion(descripcion);

        return convertirAMap(permisoRepository.save(permiso));
    }

    private Permiso buscarPermiso(Long idPermiso) {
        return permisoRepository.findById(idPermiso)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Permiso no encontrado"));
    }

    private String obtenerTexto(Map<String,Object> datos,String campo) {
        if (datos == null || datos.get(campo) == null) return "";
        return datos.get(campo).toString().trim();
    }

    private void validar(String codigo,String nombre) {
        if (codigo.isBlank() || !codigo.matches("[A-Z0-9_]+") || codigo.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El código del permiso no es válido");
        }

        if (nombre.isBlank() || nombre.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El nombre del permiso es obligatorio y debe tener máximo 120 caracteres");
        }
    }

    private Map<String,Object> convertirAMap(Permiso permiso) {
        Map<String,Object> resultado = new HashMap<>();
        resultado.put("idPermiso",permiso.getIdPermiso());
        resultado.put("codigo",permiso.getCodigo());
        resultado.put("nombre",permiso.getNombre());
        resultado.put("descripcion",permiso.getDescripcion());
        return resultado;
    }
}

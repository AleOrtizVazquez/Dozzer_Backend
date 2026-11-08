
package com.dozzer.service;

import java.util.List;
import java.util.Map;

public interface RolService {

    List<Map<String,Object>> listarRoles();

    Map<String,Object> obtenerRol(Long idRol);

    Map<String,Object> crearRol(Map<String,Object> datos);

    Map<String,Object> actualizarRol(Long idRol,Map<String,Object> datos);

    Map<String,Object> asignarPermisos(Long idRol,List<Long> idsPermisos);

}

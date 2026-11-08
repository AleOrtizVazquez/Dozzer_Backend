
package com.dozzer.service;

import java.util.List;
import java.util.Map;

public interface PermisoService {

    List<Map<String,Object>> listarPermisos();

    Map<String,Object> obtenerPermiso(Long idPermiso);

    Map<String,Object> crearPermiso(Map<String,Object> datos);

    Map<String,Object> actualizarPermiso(Long idPermiso,Map<String,Object> datos);

}

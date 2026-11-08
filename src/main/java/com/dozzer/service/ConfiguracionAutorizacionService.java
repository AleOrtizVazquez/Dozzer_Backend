package com.dozzer.service;

import java.util.List;
import java.util.Map;

public interface ConfiguracionAutorizacionService {

    List<Map<String, Object>> listarConfiguraciones();

    Map<String, Object> obtenerConfiguracion(String tipoOperacion);

    Map<String, Object> actualizarConfiguracion(String tipoOperacion, Map<String, Object> datos);

}

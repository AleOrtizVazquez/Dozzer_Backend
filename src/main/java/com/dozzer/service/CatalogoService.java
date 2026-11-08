package com.dozzer.service;


import java.util.List;
import java.util.Map;

public interface CatalogoService {

    List<Map<String, Object>> listar(String catalogo);

    Map<String, Object> crear(String catalogo, Map<String, Object> datos);

    Map<String, Object> actualizar(String catalogo, Long id, Map<String, Object> datos);

    Map<String, Object> eliminar(String catalogo, Long id);
}

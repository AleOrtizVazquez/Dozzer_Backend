package com.dozzer.service;


import java.util.List;
import java.util.Map;

public interface BienService {

    List<Map<String, Object>> listarBienes();

    Map<String, Object> subirFotoAlta(byte[] foto, String tipo);

    Map<String, Object> cambiarFoto(Long idBien, byte[] foto, String tipo);

    Map<String, Object> obtenerBien(Long idBien);

    Map<String, Object> crearBien(Map<String, Object> datos);

    Map<String, Object> actualizarBien(Long idBien, Map<String, Object> datos);

    Map<String, Object> eliminarBien(Long idBien);

    Map<String,Object> importarBienes(List<Map<String,Object>> filas);


}

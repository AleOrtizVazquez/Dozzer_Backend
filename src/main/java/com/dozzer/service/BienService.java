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

    // Baja con el motivo (dañado, extraviado, obsoleto…)
    Map<String, Object> eliminarBien(Long idBien, String motivo);

    Map<String,Object> importarBienes(List<Map<String,Object>> filas);

    // Mover / reasignar uno o varios bienes (ubicación, responsable, usuario directo)
    Map<String,Object> moverBienes(Map<String,Object> datos);

    List<Map<String,Object>> movimientos(Long idBien);

    Map<String,Object> siguienteClave(Long idUbicacion);


}

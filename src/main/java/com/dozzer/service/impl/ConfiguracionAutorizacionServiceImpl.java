package com.dozzer.service.impl;


import com.dozzer.entity.ConfiguracionAutorizacion;
import com.dozzer.repository.ConfiguracionAutorizacionRepository;
import com.dozzer.service.ConfiguracionAutorizacionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class ConfiguracionAutorizacionServiceImpl implements ConfiguracionAutorizacionService {

    @Autowired
    private ConfiguracionAutorizacionRepository configuracionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarConfiguraciones() {
        return configuracionRepository.findAll().stream()
                .map(this::convertirAMap)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> obtenerConfiguracion(String tipoOperacion) {
        ConfiguracionAutorizacion configuracion = buscarConfiguracion(tipoOperacion);
        return convertirAMap(configuracion);
    }

    @Override
    public Map<String, Object> actualizarConfiguracion(String tipoOperacion, Map<String, Object> datos) {

        ConfiguracionAutorizacion configuracion = buscarConfiguracion(tipoOperacion);

        if (datos == null || !(datos.get("requiereAutorizacion") instanceof Boolean)) {
            throw new RuntimeException("Debe indicar requiereAutorizacion como true o false");
        }

        configuracion.setRequiereAutorizacion((Boolean) datos.get("requiereAutorizacion"));

        return convertirAMap(configuracionRepository.save(configuracion));
    }

    private ConfiguracionAutorizacion buscarConfiguracion(String tipoOperacion) {
        if (tipoOperacion == null || tipoOperacion.isBlank()) {
            throw new RuntimeException("El tipo de operación es obligatorio");
        }

        return configuracionRepository.findByTipoOperacion(tipoOperacion.trim().toUpperCase())
                .orElseThrow(() -> new RuntimeException("Configuración de autorización no encontrada"));
    }

    private Map<String, Object> convertirAMap(ConfiguracionAutorizacion configuracion) {
        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("idConfiguracion", configuracion.getIdConfiguracion());
        resultado.put("tipoOperacion", configuracion.getTipoOperacion());
        resultado.put("requiereAutorizacion", configuracion.getRequiereAutorizacion());
        return resultado;
    }
}

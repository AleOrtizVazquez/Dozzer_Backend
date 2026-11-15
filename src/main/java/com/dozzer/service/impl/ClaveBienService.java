package com.dozzer.service.impl;

import com.dozzer.entity.Bien;
import com.dozzer.entity.Sede;
import com.dozzer.repository.BienRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;

// Clave automática de un bien: prefijo de la sede + el número siguiente (ej. CE1839)
@Service
public class ClaveBienService {

    private static final Locale MX = new Locale("es", "MX");

    @Autowired private BienRepository bienRepository;

    public String siguienteClave(Sede sede) {
        String prefijo = sede == null || sede.getPrefijoClave() == null ? "" : sede.getPrefijoClave().trim().toUpperCase(MX);
        if (prefijo.isBlank()) prefijo = "B";
        int max = 0, digitos = 4;
        for (Bien x : bienRepository.findByClaveStartingWithIgnoreCase(prefijo)) {
            String clave = x.getClave() == null ? "" : x.getClave().trim();
            if (clave.length() <= prefijo.length()) continue;
            String resto = clave.substring(prefijo.length());
            if (!resto.matches("[0-9]{1,9}")) continue;
            max = Math.max(max, Integer.parseInt(resto));
            digitos = Math.max(digitos, resto.length());
        }
        String clave;
        do { clave = prefijo + String.format("%0" + digitos + "d", ++max); } while (bienRepository.existsByClave(clave));
        return clave;
    }
}

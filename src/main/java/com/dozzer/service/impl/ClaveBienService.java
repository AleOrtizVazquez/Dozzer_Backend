package com.dozzer.service.impl;

import com.dozzer.entity.Bien;
import com.dozzer.entity.Sede;
import com.dozzer.repository.BienRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// Clave automática de un bien: prefijo de la sede + el número siguiente (ej. CE1839)
@Service
public class ClaveBienService {

    private static final Locale MX = new Locale("es", "MX");

    @Autowired private BienRepository bienRepository;

    public String siguienteClave(Sede sede) {
        String prefijo = sede == null || sede.getPrefijoClave() == null ? "" : sede.getPrefijoClave().trim().toUpperCase(MX);
        if (prefijo.isBlank()) prefijo = "B";
        // Solo cuentan las claves con el largo habitual de la sede (ej. CE + 4 dígitos). Una clave tecleada
        // con otro largo (ej. ED009876) no debe disparar la numeración.
        Map<Integer, Integer> porLargo = new HashMap<>();
        Map<Integer, Integer> mayorPorLargo = new HashMap<>();
        for (Bien x : bienRepository.findByClaveStartingWithIgnoreCase(prefijo)) {
            String c = x.getClave() == null ? "" : x.getClave().trim();
            if (c.length() <= prefijo.length()) continue;
            String resto = c.substring(prefijo.length());
            if (!resto.matches("[0-9]{1,9}")) continue;
            porLargo.merge(resto.length(), 1, Integer::sum);
            mayorPorLargo.merge(resto.length(), Integer.parseInt(resto), Math::max);
        }
        int digitos = porLargo.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey).orElse(4);
        digitos = Math.max(digitos, 4);
        int max = mayorPorLargo.getOrDefault(digitos, 0);
        String clave;
        do { clave = prefijo + String.format("%0" + digitos + "d", ++max); } while (bienRepository.existsByClave(clave));
        return clave;
    }
}

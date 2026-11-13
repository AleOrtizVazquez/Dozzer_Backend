package com.dozzer.service.impl;

import com.dozzer.entity.Usuario;
import com.dozzer.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

// Cada mañana avisa a los administradores de calibraciones y mantenimientos que llegan:
// a 30 días, a 7 días, un día antes y el mismo día; los vencidos se recuerdan cada lunes.
@Service
public class AvisoCalibracionService {

    private static final Logger log = LoggerFactory.getLogger(AvisoCalibracionService.class);
    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    private static final Set<Long> HITOS = Set.of(30L, 7L, 1L, 0L);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PushService push;

    private record Pendiente(String clave, String articulo, String tipo, LocalDate fecha, Long idSede, long dias) {}

    @Scheduled(cron = "0 0 8 * * *", zone = "America/Mexico_City")
    public void avisarDiario() {
        try { avisar(LocalDate.now(MX), false); }
        catch (Exception e) { log.warn("No se pudieron preparar los avisos de calibración: {}", e.getMessage()); }
    }

    // todos=true: manda el resumen completo aunque hoy no sea un día de aviso (para el botón "probar")
    @Transactional(readOnly = true)
    public Map<String, Object> avisar(LocalDate hoy, boolean todos) {
        List<Pendiente> lista = new ArrayList<>();
        jdbc.query("select b.clave, b.articulo, b.requiere_calibracion rc, b.fecha_calibracion fc, b.requiere_mantenimiento rm, b.fecha_mantenimiento fm, u.id_sede "
                + "from bienes b left join cat_ubicaciones u on u.id_ubicacion = b.id_ubicacion "
                + "where b.activo = true and ((b.requiere_calibracion and b.fecha_calibracion is not null) or (b.requiere_mantenimiento and b.fecha_mantenimiento is not null))", rs -> {
            Long sede = rs.getObject("id_sede") == null ? null : rs.getLong("id_sede");
            if (rs.getBoolean("rc") && rs.getDate("fc") != null) {
                LocalDate f = rs.getDate("fc").toLocalDate();
                lista.add(new Pendiente(rs.getString("clave"), rs.getString("articulo"), "calibración", f, sede, ChronoUnit.DAYS.between(hoy, f)));
            }
            if (rs.getBoolean("rm") && rs.getDate("fm") != null) {
                LocalDate f = rs.getDate("fm").toLocalDate();
                lista.add(new Pendiente(rs.getString("clave"), rs.getString("articulo"), "mantenimiento", f, sede, ChronoUnit.DAYS.between(hoy, f)));
            }
        });
        boolean lunes = hoy.getDayOfWeek() == DayOfWeek.MONDAY;
        List<Pendiente> avisar = lista.stream()
                .filter(p -> todos ? p.dias() <= 30 : (HITOS.contains(p.dias()) || (p.dias() < 0 && lunes)))
                .sorted(Comparator.comparingLong(Pendiente::dias))
                .toList();
        int enviados = 0;
        if (!avisar.isEmpty()) {
            for (Usuario u : usuarioRepository.findByActivoTrue()) {
                boolean admin = u.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getNombre()));
                if (!admin) continue;
                Set<Long> sedes = u.getSedes().stream().map(s -> s.getIdSede()).collect(Collectors.toSet());
                List<Pendiente> suyos = avisar.stream().filter(p -> sedes.isEmpty() || sedes.contains(p.idSede())).toList();
                if (suyos.isEmpty()) continue;
                push.enviarDespues(Set.of(u.getIdUsuario()), titulo(suyos), cuerpo(suyos), "/?ir=calibracion", "calibracion-" + hoy);
                enviados++;
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("equipos", avisar.size());
        r.put("administradores", enviados);
        return r;
    }

    private static String titulo(List<Pendiente> l) {
        long vencidos = l.stream().filter(p -> p.dias() < 0).count();
        long hoy = l.stream().filter(p -> p.dias() == 0).count();
        if (hoy > 0) return "🛠 Hoy toca " + (hoy == 1 ? l.stream().filter(p -> p.dias() == 0).findFirst().get().tipo() : "calibración o mantenimiento");
        if (vencidos > 0 && vencidos == l.size()) return "🛠 " + vencidos + (vencidos == 1 ? " equipo vencido" : " equipos vencidos");
        return "🛠 Calibraciones y mantenimientos próximos";
    }

    private static String cuerpo(List<Pendiente> l) {
        String detalle = l.stream().limit(3).map(p -> p.clave() + " " + capital(p.articulo()) + " (" + p.tipo() + ", " + cuando(p.dias()) + ")")
                .collect(Collectors.joining(" · "));
        return l.size() > 3 ? detalle + " y " + (l.size() - 3) + " más" : detalle;
    }

    private static String cuando(long d) {
        if (d < 0) return "venció hace " + (-d) + (d == -1 ? " día" : " días");
        if (d == 0) return "hoy";
        if (d == 1) return "mañana";
        return "en " + d + " días";
    }

    private static String capital(String t) {
        if (t == null || t.isBlank()) return "";
        String s = t.trim().toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

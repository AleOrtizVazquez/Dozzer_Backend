package com.dozzer.service.impl;

import com.dozzer.entity.Bien;
import com.dozzer.entity.Usuario;
import com.dozzer.repository.BienRepository;
import com.dozzer.repository.LevantamientoRepository;
import com.dozzer.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

// Etiquetas QR en blanco: se imprimen por lotes con códigos consecutivos y se ligan al bien al pegarlas.
// El QR solo lleva el código; los datos del bien viven en el sistema.
@Service
@Transactional
public class EtiquetaService {

    public static final int MAX_LOTE = 900;   // 30 hojas de 30

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BienRepository bienRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private LevantamientoRepository levantamientoRepository;

    // ===================== Consulta =====================

    @Transactional(readOnly = true)
    public Map<String, Object> resumen() {
        soloAdmin();
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> c = jdbc.queryForMap("select count(*) total, count(*) filter (where estado = 'ASIGNADA') asignadas, " +
                "count(*) filter (where estado = 'LIBRE') libres, count(*) filter (where estado = 'ANULADA') anuladas, " +
                "coalesce(max(numero), 0) ultimo from etiquetas");
        r.putAll(c);
        // Por sede: bienes activos que aún no tienen etiqueta (lo que falta por imprimir y pegar)
        r.put("porSede", jdbc.queryForList("select s.nombre_sede sede, count(*) activos, count(b.etiqueta) con_etiqueta, " +
                "count(*) - count(b.etiqueta) sin_etiqueta from bienes b join cat_ubicaciones u on u.id_ubicacion = b.id_ubicacion " +
                "join cat_sedes s on s.id_sede = u.id_sede where b.activo group by s.nombre_sede order by s.nombre_sede"));
        return r;
    }

    // Códigos libres (impresos y sin pegar), para volver a imprimirlos si la hoja salió mal
    @Transactional(readOnly = true)
    public List<String> libres(int limite) {
        soloAdmin();
        return jdbc.queryForList("select codigo from etiquetas where estado = 'LIBRE' order by numero limit ?", String.class,
                Math.max(1, Math.min(limite, MAX_LOTE)));
    }

    // Qué hay detrás de un código (al escanear): no revela datos si el bien no es de una sede del usuario
    @Transactional(readOnly = true)
    public Map<String, Object> consultar(String codigo) {
        String cod = normalizar(codigo);
        List<Map<String, Object>> f = jdbc.queryForList("select codigo, estado, id_bien from etiquetas where codigo = ?", cod);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("codigo", cod);
        if (f.isEmpty()) { r.put("estado", "NO_EXISTE"); return r; }
        r.put("estado", f.get(0).get("estado"));
        Object idBien = f.get(0).get("id_bien");
        if (idBien != null && "ASIGNADA".equals(f.get(0).get("estado"))) {
            bienRepository.findById(((Number) idBien).longValue()).ifPresent(b -> {
                if (!puedeVerSede(usuarioActual(), idSede(b))) return;
                r.put("idBien", b.getIdBien());
                r.put("clave", b.getClave());
                r.put("articulo", b.getArticulo());
                r.put("sede", b.getUbicacion() != null && b.getUbicacion().getSede() != null ? b.getUbicacion().getSede().getNombreSede() : null);
            });
        }
        return r;
    }

    // ===================== Generar lote =====================

    public Map<String, Object> generarLote(int cantidad) {
        soloAdmin();
        if (cantidad < 1 || cantidad > MAX_LOTE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Genera entre 1 y " + MAX_LOTE + " etiquetas por vez");
        }
        jdbc.execute("lock table etiquetas in exclusive mode");
        int ultimo = Optional.ofNullable(jdbc.queryForObject("select coalesce(max(numero), 0) from etiquetas", Integer.class)).orElse(0);
        int lote = Optional.ofNullable(jdbc.queryForObject("select coalesce(max(lote), 0) + 1 from etiquetas", Integer.class)).orElse(1);
        List<Object[]> filas = new ArrayList<>();
        List<String> codigos = new ArrayList<>();
        for (int i = 1; i <= cantidad; i++) {
            int n = ultimo + i;
            String cod = codigo(n);
            codigos.add(cod);
            filas.add(new Object[]{n, cod, lote});
        }
        jdbc.batchUpdate("insert into etiquetas (numero, codigo, estado, lote, fecha_creacion) values (?, ?, 'LIBRE', ?, now())", filas);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("lote", lote);
        r.put("codigos", codigos);
        return r;
    }

    // ===================== Asignar / liberar =====================

    // Pega la etiqueta en un bien. Si el bien ya tenía otra, solo se reemplaza si se pide (la anterior queda anulada).
    public Map<String, Object> asignar(String codigo, Long idBien, boolean reemplazar) {
        Usuario u = usuarioActual();
        String cod = normalizar(codigo);
        if (idBien == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el bien");
        Bien bien = bienRepository.findByIdBienAndActivoTrue(idBien)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "El bien no existe o está dado de baja"));
        if (!puedeEtiquetar(u, idSede(bien))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes etiquetar bienes de esta sede");

        List<Map<String, Object>> f = jdbc.queryForList("select estado, id_bien from etiquetas where codigo = ? for update", cod);
        if (f.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "La etiqueta " + cod + " no existe. Imprímela desde DOZZER.");
        String estado = String.valueOf(f.get(0).get("estado"));
        Object actual = f.get(0).get("id_bien");
        if ("ASIGNADA".equals(estado) && actual != null && ((Number) actual).longValue() == idBien) {
            return resultado(bien, cod, false);   // ya estaba pegada en este bien
        }
        if (!"LIBRE".equals(estado)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ASIGNADA".equals(estado)
                    ? "La etiqueta " + cod + " ya está pegada en otro bien" : "La etiqueta " + cod + " está anulada; usa otra");
        }
        String anterior = bien.getEtiqueta();
        if (anterior != null && !anterior.isBlank()) {
            if (!reemplazar) throw new ResponseStatusException(HttpStatus.CONFLICT, "Este bien ya tiene la etiqueta " + anterior);
            jdbc.update("update etiquetas set estado = 'ANULADA' where codigo = ?", anterior);
        }
        bien.setEtiqueta(cod);
        bienRepository.saveAndFlush(bien);
        jdbc.update("update etiquetas set estado = 'ASIGNADA', id_bien = ?, fecha_asignacion = now(), id_usuario_asigno = ? where codigo = ?",
                idBien, u.getIdUsuario(), cod);
        return resultado(bien, cod, anterior != null && !anterior.isBlank());
    }

    // Corrige un error: la etiqueta se despega del bien y vuelve a quedar libre
    public Map<String, Object> liberar(String codigo) {
        soloAdmin();
        String cod = normalizar(codigo);
        List<Map<String, Object>> f = jdbc.queryForList("select id_bien from etiquetas where codigo = ? for update", cod);
        if (f.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "La etiqueta no existe");
        Object idBien = f.get(0).get("id_bien");
        if (idBien != null) {
            bienRepository.findById(((Number) idBien).longValue()).ifPresent(b -> {
                if (cod.equals(b.getEtiqueta())) { b.setEtiqueta(null); bienRepository.saveAndFlush(b); }
            });
        }
        jdbc.update("update etiquetas set estado = 'LIBRE', id_bien = null, fecha_asignacion = null, id_usuario_asigno = null where codigo = ?", cod);
        return Map.of("success", true, "codigo", cod);
    }

    // ===================== Apoyo =====================

    private Map<String, Object> resultado(Bien b, String cod, boolean reemplazo) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("codigo", cod);
        r.put("idBien", b.getIdBien());
        r.put("clave", b.getClave());
        r.put("reemplazo", reemplazo);
        return r;
    }

    public static String codigo(int n) {
        return String.format("DZ-%06d", n);
    }

    // "dz000012", "DZ-12" y "DZ-000012" son la misma etiqueta
    public static String normalizar(String c) {
        String t = c == null ? "" : c.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (!t.matches("DZ[0-9]{1,9}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Código de etiqueta no válido");
        return codigo(Integer.parseInt(t.substring(2)));
    }

    private static Long idSede(Bien b) {
        return b.getUbicacion() != null && b.getUbicacion().getSede() != null ? b.getUbicacion().getSede().getIdSede() : null;
    }

    private static boolean esAdmin(Usuario u) {
        return u.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getNombre()));
    }

    private boolean puedeVerSede(Usuario u, Long idSede) {
        if (u.getSedes().isEmpty()) return esAdmin(u);
        return idSede != null && u.getSedes().stream().anyMatch(s -> idSede.equals(s.getIdSede()));
    }

    // Admin (general o de la sede) o el capturista de un levantamiento abierto en esa sede
    private boolean puedeEtiquetar(Usuario u, Long idSede) {
        if (esAdmin(u) && (u.getSedes().isEmpty() || u.getSedes().stream().anyMatch(s -> s.getIdSede().equals(idSede)))) return true;
        return idSede != null && levantamientoRepository.findAllByOrderByIdLevantamientoDesc().stream()
                .anyMatch(l -> "EN_CURSO".equals(l.getEstado()) && idSede.equals(l.getIdSede()) && u.getIdUsuario().equals(l.getIdUsuarioCapturista()));
    }

    private void soloAdmin() {
        if (!esAdmin(usuarioActual())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el administrador maneja las etiquetas");
    }

    private Usuario usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        return usuarioRepository.findByCorreoIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }
}

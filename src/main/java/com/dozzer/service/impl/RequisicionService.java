package com.dozzer.service.impl;

import com.dozzer.entity.Documento;
import com.dozzer.entity.Persona;
import com.dozzer.entity.Sede;
import com.dozzer.entity.Usuario;
import com.dozzer.repository.DocumentoRepository;
import com.dozzer.repository.SedeRepository;
import com.dozzer.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

// Requisición de compra FA-04-01-02: la pide cualquiera, la autoriza el revisor/admin de la sede y la aprueba un admin.
// Se guarda en "documentos" (tipo REQUISICION); el PDF se arma en el navegador con estos datos.
@Service
public class RequisicionService {

    private static final Logger log = LoggerFactory.getLogger(RequisicionService.class);
    public static final String TIPO = "REQUISICION";
    private static final Set<String> CATEGORIAS = Set.of("AM", "MEO", "ET", "ECA", "ME", "H", "MI", "O");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yy");

    @Autowired private DocumentoRepository documentoRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private SedeRepository sedeRepository;
    @Autowired private PushService push;

    // ---------- Consultas ----------

    @Transactional(readOnly = true)
    public Map<String, Object> misDatos() {
        Usuario u = actual();
        Persona p = u.getPersona();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nombre", nombre(u));
        r.put("cargo", p != null && p.getPuesto() != null ? texto(p.getPuesto().getNombrePuesto()) : "");
        r.put("area", p != null && p.getPuesto() != null && p.getPuesto().getDepartamento() != null
                ? texto(p.getPuesto().getDepartamento().getNombreDepartamento()) : "");
        Sede s = p != null ? p.getSede() : null;
        r.put("idSede", s != null ? s.getIdSede() : null);
        r.put("sede", s != null ? s.getNombreSede() : "");
        return r;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar() {
        Usuario u = actual();
        return documentoRepository.findByTipoOrderByIdDocumentoDesc(TIPO).stream()
                .filter(d -> puedeVer(u, d))
                .map(d -> resumen(d, u, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtener(Long id) {
        Usuario u = actual();
        Documento d = buscar(id);
        if (!puedeVer(u, d)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a esta requisición");
        return resumen(d, u, true);
    }

    // ---------- Crear ----------

    @Transactional
    public Map<String, Object> crear(Map<String, Object> datos) {
        Usuario u = actual();
        Map<String, Object> yo = misDatos();
        String area = limpio(datos.get("area"), 80);
        String cargo = limpio(datos.get("cargo"), 80);
        if (area.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe el área que lo solicita");
        if (cargo.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe tu cargo");

        String fechaRequerida = limpio(datos.get("fechaRequerida"), 10);
        LocalDate requerida;
        try { requerida = LocalDate.parse(fechaRequerida); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica para cuándo se requiere el material"); }
        if (requerida.isBefore(LocalDate.now().minusDays(1)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha requerida no puede ser anterior a hoy");

        List<Map<String, Object>> partidas = new ArrayList<>();
        if (datos.get("partidas") instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String desc = limpio(m.get("descripcion"), 160);
                if (desc.isBlank()) continue;
                String cat = limpio(m.get("categoria"), 4).toUpperCase();
                if (!CATEGORIAS.contains(cat)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige la categoría de \"" + desc + "\"");
                double cant;
                try { cant = Double.parseDouble(String.valueOf(m.get("cantidad"))); } catch (Exception e) { cant = 0; }
                if (!(cant > 0) || cant > 1_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Revisa la cantidad de \"" + desc + "\"");
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("categoria", cat);
                p.put("descripcion", desc);
                p.put("unidad", limpio(m.get("unidad"), 12).isBlank() ? "PZA" : limpio(m.get("unidad"), 12).toUpperCase());
                p.put("cantidad", cant == Math.floor(cant) ? (Object) (long) cant : cant);
                p.put("observaciones", limpio(m.get("observaciones"), 160));
                partidas.add(p);
            }
        }
        if (partidas.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agrega al menos una partida");
        if (partidas.size() > 30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Máximo 30 partidas por requisición");

        Long idSede = yo.get("idSede") == null ? null : Long.valueOf(String.valueOf(yo.get("idSede")));
        if (idSede == null && !u.getSedes().isEmpty()) idSede = u.getSedes().iterator().next().getIdSede();
        String sede = idSede == null ? "" : sedeRepository.findById(idSede).map(Sede::getNombreSede).orElse("");

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("formato", "FA-04-01-02");
        d.put("revision", "3");
        d.put("sede", sede);
        d.put("area", area);
        d.put("cargo", cargo);
        d.put("solicitante", nombre(u));
        d.put("fechaEmision", LocalDate.now().format(FECHA));
        d.put("fechaRequerida", requerida.toString());
        d.put("partidas", partidas);
        Map<String, Object> firmas = new LinkedHashMap<>();
        String f = firmaValida(datos.get("firma"));
        if (f != null) firmas.put("solicito", f);
        d.put("firmas", firmas);
        Map<String, Object> nombres = new LinkedHashMap<>();
        nombres.put("solicito", nombre(u));
        d.put("nombres", nombres);

        Documento doc = new Documento();
        doc.setTipo(TIPO);
        doc.setEstado("PENDIENTE");
        doc.setIdSede(idSede);
        doc.setIdUsuarioSolicitante(u.getIdUsuario());
        doc.setDatos(d);
        doc.setFechaCreacion(LocalDateTime.now());
        long n = documentoRepository.countByTipo(TIPO) + 1;
        String folio;
        do { folio = "RQ-" + String.format("%04d", n++); } while (documentoRepository.existsByFolio(folio));
        doc.setFolio(folio);
        d.put("folio", folio);
        documentoRepository.save(doc);

        avisar(destinatarios(idSede, false, u.getIdUsuario()), "Nueva requisición por autorizar",
                nombre(u) + " · " + resumenPartidas(partidas), doc);
        return resumen(doc, u, true);
    }

    // ---------- Autorizar, aprobar, rechazar, cancelar ----------

    @Transactional
    public Map<String, Object> autorizar(Long id, Map<String, Object> datos) {
        Usuario u = actual();
        Documento doc = buscar(id);
        if (!"PENDIENTE".equals(doc.getEstado())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta requisición ya no espera autorización");
        if (!puedeAutorizar(u, doc.getIdSede())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes autorizar requisiciones de esta sede");
        firmarPaso(doc, u, "autorizo", datos);
        doc.setEstado("AUTORIZADA");
        doc.setIdUsuarioAutorizo(u.getIdUsuario());
        documentoRepository.save(doc);
        avisar(destinatarios(doc.getIdSede(), true, u.getIdUsuario()), "Requisición autorizada: falta aprobarla",
                doc.getFolio() + " · " + datosDe(doc).get("solicitante"), doc);
        avisar(Set.of(doc.getIdUsuarioSolicitante()), "Tu requisición fue autorizada", doc.getFolio() + " · ahora falta la aprobación", doc);
        return resumen(doc, u, true);
    }

    @Transactional
    public Map<String, Object> aprobar(Long id, Map<String, Object> datos) {
        Usuario u = actual();
        Documento doc = buscar(id);
        if (!"AUTORIZADA".equals(doc.getEstado())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Primero debe autorizarse la requisición");
        if (!puedeAprobar(u, doc.getIdSede())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo un administrador puede aprobarla");
        firmarPaso(doc, u, "aprobo", datos);
        doc.setEstado("APROBADA");
        documentoRepository.save(doc);
        avisar(Set.of(doc.getIdUsuarioSolicitante()), "Tu requisición fue aprobada ✅", doc.getFolio() + " · " + resumenPartidas(partidasDe(doc)), doc);
        return resumen(doc, u, true);
    }

    @Transactional
    public Map<String, Object> rechazar(Long id, Map<String, Object> datos) {
        Usuario u = actual();
        Documento doc = buscar(id);
        boolean pendiente = "PENDIENTE".equals(doc.getEstado()), autorizada = "AUTORIZADA".equals(doc.getEstado());
        if (!pendiente && !autorizada) throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta requisición ya está resuelta");
        if (pendiente ? !puedeAutorizar(u, doc.getIdSede()) : !puedeAprobar(u, doc.getIdSede()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes rechazar esta requisición");
        String motivo = limpio(datos == null ? null : datos.get("motivo"), 300);
        if (motivo.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe el motivo del rechazo");
        Map<String, Object> d = new LinkedHashMap<>(datosDe(doc));
        d.put("motivoRechazo", motivo);
        d.put("rechazoPor", nombre(u));
        d.put("fechaRechazo", LocalDate.now().format(FECHA));
        doc.setDatos(d);
        doc.setEstado("RECHAZADA");
        documentoRepository.save(doc);
        avisar(Set.of(doc.getIdUsuarioSolicitante()), "Tu requisición fue rechazada", doc.getFolio() + " · Motivo: " + motivo, doc);
        return resumen(doc, u, true);
    }

    @Transactional
    public Map<String, Object> cancelar(Long id) {
        Usuario u = actual();
        Documento doc = buscar(id);
        if (!u.getIdUsuario().equals(doc.getIdUsuarioSolicitante()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo quien la pidió puede cancelarla");
        if (!"PENDIENTE".equals(doc.getEstado())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya no se puede cancelar: ya fue revisada");
        doc.setEstado("CANCELADA");
        documentoRepository.save(doc);
        return resumen(doc, u, true);
    }

    private void firmarPaso(Documento doc, Usuario u, String lugar, Map<String, Object> datos) {
        Map<String, Object> d = new LinkedHashMap<>(datosDe(doc));
        Map<String, Object> firmas = new LinkedHashMap<>();
        if (d.get("firmas") instanceof Map<?, ?> m) m.forEach((k, v) -> firmas.put(String.valueOf(k), v));
        String f = firmaValida(datos == null ? null : datos.get("firma"));
        if (f != null) firmas.put(lugar, f); else firmas.remove(lugar);
        d.put("firmas", firmas);
        Map<String, Object> nombres = new LinkedHashMap<>();
        if (d.get("nombres") instanceof Map<?, ?> m) m.forEach((k, v) -> nombres.put(String.valueOf(k), v));
        nombres.put(lugar, nombre(u));
        d.put("nombres", nombres);
        Map<String, Object> fechas = new LinkedHashMap<>();
        if (d.get("fechas") instanceof Map<?, ?> m) m.forEach((k, v) -> fechas.put(String.valueOf(k), v));
        fechas.put(lugar, LocalDate.now().format(FECHA));
        d.put("fechas", fechas);
        doc.setDatos(d);
    }

    // ---------- Permisos ----------

    private boolean esAdmin(Usuario u) {
        return u.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getNombre()));
    }

    private boolean deLaSede(Usuario u, Long idSede) {
        return idSede != null && u.getSedes().stream().anyMatch(s -> idSede.equals(s.getIdSede()));
    }

    private boolean puedeAprobar(Usuario u, Long idSede) {
        return esAdmin(u) && (u.getSedes().isEmpty() || deLaSede(u, idSede));
    }

    private boolean puedeAutorizar(Usuario u, Long idSede) {
        if (puedeAprobar(u, idSede)) return true;
        boolean revisor = u.getRoles().stream().flatMap(r -> r.getPermisos().stream())
                .anyMatch(p -> "SOLICITUDES_AUTORIZAR".equals(p.getCodigo()));
        return revisor && deLaSede(u, idSede);
    }

    private boolean puedeVer(Usuario u, Documento d) {
        return u.getIdUsuario().equals(d.getIdUsuarioSolicitante()) || puedeAutorizar(u, d.getIdSede());
    }

    // ---------- Avisos al celular ----------

    private Set<Long> destinatarios(Long idSede, boolean soloAprobadores, Long excepto) {
        return usuarioRepository.findByActivoTrue().stream()
                .filter(x -> !Objects.equals(x.getIdUsuario(), excepto))
                .filter(x -> soloAprobadores ? puedeAprobar(x, idSede) : puedeAutorizar(x, idSede))
                .map(Usuario::getIdUsuario)
                .collect(Collectors.toSet());
    }

    private void avisar(Set<Long> ids, String titulo, String cuerpo, Documento doc) {
        try {
            if (ids == null || ids.isEmpty()) return;
            push.enviarDespues(ids, titulo, cuerpo, "/?ir=requisiciones", "requisicion-" + doc.getIdDocumento());
        } catch (Exception e) {
            log.warn("No se pudo preparar el aviso de requisición: {}", e.getMessage());
        }
    }

    // ---------- Utilidades ----------

    private Map<String, Object> resumen(Documento d, Usuario u, boolean completo) {
        Map<String, Object> datos = datosDe(d);
        List<Map<String, Object>> partidas = partidasDe(d);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("idDocumento", d.getIdDocumento());
        r.put("folio", d.getFolio());
        r.put("estado", d.getEstado());
        r.put("fechaCreacion", d.getFechaCreacion());
        r.put("sede", datos.get("sede"));
        r.put("area", datos.get("area"));
        r.put("solicitante", datos.get("solicitante"));
        r.put("fechaRequerida", datos.get("fechaRequerida"));
        r.put("totalPartidas", partidas.size());
        r.put("resumen", resumenPartidas(partidas));
        r.put("motivoRechazo", datos.get("motivoRechazo"));
        r.put("rechazoPor", datos.get("rechazoPor"));
        r.put("nombres", datos.get("nombres"));
        boolean mia = u.getIdUsuario().equals(d.getIdUsuarioSolicitante());
        r.put("mia", mia);
        r.put("puedoAutorizar", "PENDIENTE".equals(d.getEstado()) && puedeAutorizar(u, d.getIdSede()));
        r.put("puedoAprobar", "AUTORIZADA".equals(d.getEstado()) && puedeAprobar(u, d.getIdSede()));
        r.put("puedoCancelar", "PENDIENTE".equals(d.getEstado()) && mia);
        if (completo) r.put("datos", datos);
        return r;
    }

    private static String resumenPartidas(List<Map<String, Object>> partidas) {
        String t = partidas.stream().limit(3)
                .map(p -> p.get("descripcion") + " ×" + p.get("cantidad"))
                .collect(Collectors.joining(", "));
        return partidas.size() > 3 ? t + " y " + (partidas.size() - 3) + " más" : t;
    }

    private static Map<String, Object> datosDe(Documento d) {
        return d.getDatos() == null ? Map.of() : d.getDatos();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> partidasDe(Documento d) {
        Object p = datosDe(d).get("partidas");
        if (!(p instanceof List<?> l)) return List.of();
        return l.stream().filter(x -> x instanceof Map).map(x -> (Map<String, Object>) x).toList();
    }

    private Documento buscar(Long id) {
        Documento d = documentoRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Requisición no encontrada"));
        if (!TIPO.equals(d.getTipo())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Requisición no encontrada");
        return d;
    }

    private Usuario actual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        return usuarioRepository.findByCorreoIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }

    private static String nombre(Usuario u) {
        if (u == null) return "";
        return u.getPersona() != null && u.getPersona().getNombreCompleto() != null ? u.getPersona().getNombreCompleto() : u.getCorreo();
    }

    private static String texto(String v) { return v == null ? "" : v; }

    private static String limpio(Object v, int max) {
        String s = v == null ? "" : String.valueOf(v).replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    // Firma dibujada en pantalla: PNG en base64 de tamaño razonable (vacía = firma en papel)
    private static String firmaValida(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.isBlank()) return null;
        if (!s.startsWith("data:image/png;base64,") || s.length() > 200_000 || !s.substring(22).matches("[A-Za-z0-9+/=]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La firma no es válida; vuelve a dibujarla");
        return s;
    }
}

package com.dozzer.service.impl;

import com.dozzer.entity.*;
import com.dozzer.repository.DocumentoRepository;
import com.dozzer.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

// Registro de los formatos de Alta/Baja. Se guarda una copia congelada de los datos (≈2 KB);
// el navegador arma el PDF con ellos, así que siempre sale igual aunque el bien cambie después.
@Service
@Transactional
public class DocumentoFormatoService {

    private static final DateTimeFormatter FECHA =
            DateTimeFormatter.ofPattern("dd 'DE' MMMM 'DE' yyyy", new Locale("es", "MX"));

    @Autowired private DocumentoRepository documentoRepository;
    @Autowired private UsuarioRepository usuarioRepository;

    // ---------- Registro ----------

    public Documento registrar(String tipo, Bien bien, SolicitudBien solicitud,
                               Usuario solicitante, Usuario autorizo, String observacionesExtra) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("tipo", tipo);
        d.put("folio", "");
        String sede = bien.getUbicacion() != null && bien.getUbicacion().getSede() != null
                ? bien.getUbicacion().getSede().getNombreSede() : "";
        d.put("sede", sede);
        LocalDateTime fecha = solicitud != null && solicitud.getFechaSolicitud() != null
                ? solicitud.getFechaSolicitud() : LocalDateTime.now();
        d.put("fecha", fecha.format(FECHA).toUpperCase(new Locale("es", "MX")));

        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("inventario", texto(bien.getClave()));
        fila.put("descripcion", unir(" · ", bien.getArticulo(), bien.getMarca() != null ? bien.getMarca().getNombreMarca() : null));
        fila.put("modeloSerie", unir(" / ", bien.getModelo(), bien.getNumeroSerie()));
        d.put("filas", List.of(fila));

        List<Map<String, Object>> detalle = new ArrayList<>();
        detalle.add(campo("Clasificación", bien.getClasificacion() != null ? bien.getClasificacion().getNombre() : null));
        detalle.add(campo("Ubicación", unir(" · ", bien.getUbicacion() != null ? bien.getUbicacion().getNombreArea() : null, sede)));
        detalle.add(campo("Responsable", bien.getResponsableArea() != null ? bien.getResponsableArea().getNombreCompleto() : null));
        detalle.add(campo("Estado físico", bien.getEstadoFisico()));
        detalle.add(campo("Piezas", String.valueOf(bien.getCantidadPiezas() != null ? bien.getCantidadPiezas() : 1)));
        detalle.add(campo("Usuario directo", bien.getUsuarioDirecto() != null ? bien.getUsuarioDirecto().getNombreCompleto() : null));
        d.put("detalle", detalle);

        List<String> obs = new ArrayList<>();
        if (solicitud != null && solicitud.getMotivoSolicitud() != null && !solicitud.getMotivoSolicitud().isBlank())
            obs.add("Motivo: " + solicitud.getMotivoSolicitud().trim());
        if (solicitud != null && solicitud.getMotivoResolucion() != null && !solicitud.getMotivoResolucion().isBlank())
            obs.add("Comentario del administrador: " + solicitud.getMotivoResolucion().trim());
        if (observacionesExtra != null && !observacionesExtra.isBlank()) obs.add(observacionesExtra);
        if ("ALTA".equals(tipo) && bien.getCaracteristicas() != null && !bien.getCaracteristicas().isBlank())
            obs.add("Características: " + bien.getCaracteristicas().trim());
        d.put("observaciones", String.join("\n", obs));

        d.put("solicito", nombre(solicitante));
        d.put("autorizo", nombre(autorizo));

        // Correos sugeridos para compartir
        List<Map<String, Object>> contactos = new ArrayList<>();
        if (solicitud != null && solicitante != null) contactos.add(contacto(nombre(solicitante), solicitante.getCorreo(), "Solicitó"));
        if (bien.getResponsableArea() != null) contactos.add(contacto(bien.getResponsableArea().getNombreCompleto(), bien.getResponsableArea().getCorreo(), "Responsable"));
        if (bien.getUsuarioDirecto() != null) contactos.add(contacto(bien.getUsuarioDirecto().getNombreCompleto(), bien.getUsuarioDirecto().getCorreo(), "Usuario directo"));
        d.put("contactos", contactos.stream().filter(c -> c.get("correo") != null && !c.get("correo").toString().isBlank()).toList());
        if (solicitud != null && solicitante != null) d.put("correoSugerido", solicitante.getCorreo());

        Documento doc = new Documento();
        doc.setTipo(tipo);
        doc.setEstado("VIGENTE");
        doc.setIdSede(bien.getUbicacion() != null && bien.getUbicacion().getSede() != null ? bien.getUbicacion().getSede().getIdSede()
                : solicitud != null ? solicitud.getIdSede() : null);
        doc.setIdBien(bien.getIdBien());
        doc.setIdSolicitud(solicitud != null ? solicitud.getIdSolicitud() : null);
        doc.setIdUsuarioSolicitante(solicitante != null ? solicitante.getIdUsuario() : null);
        doc.setIdUsuarioAutorizo(autorizo != null ? autorizo.getIdUsuario() : null);
        doc.setDatos(d);
        documentoRepository.saveAndFlush(doc);

        // Folio con el número del documento: A-26-0001 / B-26-0002
        String folio = ("ALTA".equals(tipo) ? "A" : "B") + "-" + String.valueOf(fecha.getYear()).substring(2)
                + "-" + String.format("%04d", doc.getIdDocumento());
        doc.setFolio(folio);
        Map<String, Object> conFolio = new LinkedHashMap<>(d);
        conFolio.put("folio", folio);
        doc.setDatos(conFolio);
        return documentoRepository.save(doc);
    }

    // Actas del inventario anual (inicio y cierre): datos ya armados por el levantamiento
    public Documento registrarActa(String tipo, Long idSede, Map<String, Object> datos, Usuario usuario, int anio) {
        Documento doc = new Documento();
        doc.setTipo(tipo);
        doc.setEstado("VIGENTE");
        doc.setIdSede(idSede);
        doc.setIdUsuarioSolicitante(usuario != null ? usuario.getIdUsuario() : null);
        doc.setIdUsuarioAutorizo(usuario != null ? usuario.getIdUsuario() : null);
        doc.setDatos(datos);
        documentoRepository.saveAndFlush(doc);
        String folio = ("ACTA_INICIO".equals(tipo) ? "AI" : "AC") + "-" + String.valueOf(anio).substring(2)
                + "-" + String.format("%04d", doc.getIdDocumento());
        doc.setFolio(folio);
        Map<String, Object> conFolio = new LinkedHashMap<>(datos);
        conFolio.put("folio", folio);
        doc.setDatos(conFolio);
        return documentoRepository.save(doc);
    }

    // Al revocar una autorización, su documento queda anulado (no se borra)
    public void anularPorSolicitud(Long idSolicitud) {
        documentoRepository.findByIdSolicitud(idSolicitud).forEach(doc -> {
            doc.setEstado("ANULADO");
            documentoRepository.save(doc);
        });
    }

    // ---------- Consulta con alcance ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar(String tipo, Long idSolicitud, Long idBien) {
        return visibles().stream()
                .filter(doc -> tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase(doc.getTipo())
                        || ("ACTAS".equalsIgnoreCase(tipo) && doc.getTipo() != null && doc.getTipo().startsWith("ACTA")))
                .filter(doc -> idSolicitud == null || idSolicitud.equals(doc.getIdSolicitud()))
                .filter(doc -> idBien == null || idBien.equals(doc.getIdBien()))
                .map(this::resumen)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> conteo() {
        Map<String, Object> r = new LinkedHashMap<>();
        List<Documento> docs = visibles();
        r.put("ALTA", docs.stream().filter(d -> "ALTA".equals(d.getTipo())).count());
        r.put("BAJA", docs.stream().filter(d -> "BAJA".equals(d.getTipo())).count());
        r.put("ACTAS", docs.stream().filter(d -> d.getTipo() != null && d.getTipo().startsWith("ACTA")).count());
        return r;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtener(Long idDocumento) {
        Documento doc = documentoRepository.findById(idDocumento)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento no encontrado"));
        if (!puedeVer(doc)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a este documento");
        Map<String, Object> r = resumen(doc);
        r.put("datos", doc.getDatos());
        return r;
    }

    // Admin sin sede asignada: todos. Admin/revisor con sedes: solo esas sedes. Empleado: solo los suyos.
    private List<Documento> visibles() {
        Alcance a = alcance();
        if (a.todos) return documentoRepository.findAllByOrderByIdDocumentoDesc();
        if (!a.sedes.isEmpty()) return documentoRepository.findByIdSedeInOrderByIdDocumentoDesc(a.sedes);
        return documentoRepository.findByIdUsuarioSolicitanteOrderByIdDocumentoDesc(a.idUsuario);
    }

    private boolean puedeVer(Documento doc) {
        Alcance a = alcance();
        if (a.todos) return true;
        if (!a.sedes.isEmpty()) return doc.getIdSede() != null && a.sedes.contains(doc.getIdSede());
        return a.idUsuario.equals(doc.getIdUsuarioSolicitante());
    }

    private record Alcance(boolean todos, Set<Long> sedes, Long idUsuario) {}

    private Alcance alcance() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        Usuario u = usuarioRepository.findByCorreoIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
        boolean admin = u.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getNombre()));
        boolean revisor = auth.getAuthorities().stream().anyMatch(x -> "SOLICITUDES_AUTORIZAR".equals(x.getAuthority()));
        Set<Long> sedes = u.getSedes().stream().map(Sede::getIdSede).collect(Collectors.toSet());
        if (admin && sedes.isEmpty()) return new Alcance(true, Set.of(), u.getIdUsuario());
        if (admin || revisor) return new Alcance(false, sedes, u.getIdUsuario());
        return new Alcance(false, Set.of(), u.getIdUsuario());
    }

    private Map<String, Object> resumen(Documento doc) {
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> d = doc.getDatos() == null ? Map.of() : doc.getDatos();
        r.put("idDocumento", doc.getIdDocumento());
        r.put("tipo", doc.getTipo());
        r.put("folio", doc.getFolio());
        r.put("estado", doc.getEstado());
        r.put("idSolicitud", doc.getIdSolicitud());
        r.put("idBien", doc.getIdBien());
        r.put("fechaCreacion", doc.getFechaCreacion());
        r.put("sede", d.get("sede"));
        r.put("solicito", d.get("solicito"));
        r.put("autorizo", d.get("autorizo"));
        if (d.get("titulo") != null) {
            r.put("clave", d.get("sede"));
            r.put("descripcion", d.get("titulo"));
        }
        Object filas = d.get("filas");
        if (filas instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> f) {
            r.put("clave", f.get("inventario"));
            r.put("descripcion", f.get("descripcion"));
        }
        return r;
    }

    // ---------- Utilidades ----------

    private static Map<String, Object> campo(String etiqueta, String valor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("etiqueta", etiqueta);
        m.put("valor", valor == null ? "" : valor);
        return m;
    }

    private static Map<String, Object> contacto(String nombre, String correo, String rol) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", nombre == null ? correo : nombre);
        m.put("correo", correo == null ? "" : correo.toLowerCase());
        m.put("rol", rol);
        return m;
    }

    private static String nombre(Usuario u) {
        if (u == null) return "";
        return u.getPersona() != null && u.getPersona().getNombreCompleto() != null
                ? u.getPersona().getNombreCompleto() : u.getCorreo();
    }

    private static String texto(String v) { return v == null ? "" : v; }

    private static String unir(String sep, String... partes) {
        return Arrays.stream(partes).filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(sep));
    }
}

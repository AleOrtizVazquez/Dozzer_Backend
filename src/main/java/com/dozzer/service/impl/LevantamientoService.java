package com.dozzer.service.impl;

import com.dozzer.entity.*;
import com.dozzer.repository.*;
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

// Inventario anual: levantamiento físico por sede, con acta de inicio y acta de cierre
@Service
@Transactional
public class LevantamientoService {

    private static final Locale MX = new Locale("es", "MX");
    private static final List<String> OPCIONES_DEFAULT = List.of("Bueno", "Regular", "Malo", "No funciona");

    @Autowired private LevantamientoRepository levantamientoRepository;
    @Autowired private LevantamientoRegistroRepository registroRepository;
    @Autowired private BienRepository bienRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private SedeRepository sedeRepository;
    @Autowired private UbicacionRepository ubicacionRepository;
    @Autowired private DocumentoFormatoService documentoFormatoService;
    @Autowired private FotoStorageService fotoStorage;
    @Autowired private DocumentoRepository documentoRepository;

    // ===================== Consulta =====================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar() {
        Usuario u = usuarioActual();
        return levantamientoRepository.findAllByOrderByIdLevantamientoDesc().stream()
                .filter(l -> puedeAdministrar(u, l.getIdSede()) || u.getIdUsuario().equals(l.getIdUsuarioCapturista()))
                .map(l -> resumen(l, u))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtener(Long id) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        validarVer(u, l);
        return resumen(l, u);
    }

    // Bienes de la sede con lo capturado (para la pantalla del celular)
    @Transactional(readOnly = true)
    public List<Map<String, Object>> bienes(Long id) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        validarVer(u, l);
        Map<Long, LevantamientoRegistro> registros = registroRepository.findByIdLevantamiento(id).stream()
                .collect(Collectors.toMap(LevantamientoRegistro::getIdBien, r -> r, (a, b) -> a));
        // Nombre del área donde se encontró cada bien (para "fuera de lugar")
        Set<Long> idsEncontradas = registros.values().stream().map(LevantamientoRegistro::getIdUbicacionEncontrada)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> areas = new HashMap<>();
        ubicacionRepository.findAllById(idsEncontradas).forEach(x -> areas.put(x.getIdUbicacion(), x.getNombreArea()));
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Bien b : bienesDeSede(l.getIdSede())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id_bien", b.getIdBien());
            m.put("clave", b.getClave());
            m.put("articulo", b.getArticulo());
            m.put("marca", b.getMarca() != null ? b.getMarca().getNombreMarca() : null);
            m.put("modelo", b.getModelo());
            m.put("numero_serie", b.getNumeroSerie());
            m.put("clasificacion", b.getClasificacion() != null ? b.getClasificacion().getNombre() : null);
            m.put("id_ubicacion", b.getUbicacion() != null ? b.getUbicacion().getIdUbicacion() : null);
            m.put("ubicacion", b.getUbicacion() != null ? b.getUbicacion().getNombreArea() : null);
            m.put("responsable", b.getResponsableArea() != null ? b.getResponsableArea().getNombreCompleto() : null);
            m.put("codigo_qr", b.getCodigoQr());
            m.put("tiene_foto", b.getFotoKey() != null);
            m.put("foto_mini", fotoStorage.urlMini(b.getFotoKey()));
            m.put("foto_url", fotoStorage.urlGrande(b.getFotoKey()));
            LevantamientoRegistro r = registros.get(b.getIdBien());
            if (r != null) {
                m.put("respuesta", r.getRespuesta());
                m.put("fuera_de_lugar", Boolean.TRUE.equals(r.getFueraDeLugar()));
                m.put("ubicacion_encontrada", areas.get(r.getIdUbicacionEncontrada()));
                m.put("fecha_registro", r.getFecha());
            }
            lista.add(m);
        }
        return lista;
    }

    // Usuarios que pueden ser capturistas en una sede
    @Transactional(readOnly = true)
    public List<Map<String, Object>> candidatos(Long idSede) {
        Usuario u = usuarioActual();
        if (!puedeAdministrar(u, idSede)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No administras esta sede");
        return usuarioRepository.findByActivoTrue().stream()
                .filter(x -> x.getSedes().stream().anyMatch(s -> s.getIdSede().equals(idSede)) || esAdmin(x))
                .sorted(Comparator.comparing(LevantamientoService::nombre, String.CASE_INSENSITIVE_ORDER))
                .map(x -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("idUsuario", x.getIdUsuario());
                    m.put("nombre", nombre(x));
                    m.put("correo", x.getCorreo());
                    m.put("rol", esAdmin(x) ? "Administrador" : "Empleado");
                    return m;
                }).toList();
    }

    // ===================== Crear (Acta de inicio) =====================

    public Map<String, Object> crear(Map<String, Object> datos) {
        Usuario u = usuarioActual();
        Long idSede = largo(datos.get("idSede"));
        if (idSede == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona la sede");
        if (!puedeAdministrar(u, idSede)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No administras esta sede");
        if (levantamientoRepository.existsByIdSedeAndEstado(idSede, "EN_CURSO")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta sede ya tiene un levantamiento en curso");
        }
        Sede sede = sedeRepository.findById(idSede)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "La sede no existe"));

        Long idCapturista = largo(datos.get("idUsuarioCapturista"));
        if (idCapturista == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona a la persona que va a capturar");
        Usuario capturista = usuarioRepository.findById(idCapturista)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "El capturista no existe"));

        List<Map<String, Object>> participantes = new ArrayList<>();
        if (datos.get("participantes") instanceof List<?> lista) {
            for (Object o : lista) {
                if (o instanceof Map<?, ?> p) {
                    String n = texto(p.get("nombre")), c = texto(p.get("cargo"));
                    if (!n.isBlank()) participantes.add(Map.of("nombre", limitar(n, 120), "cargo", limitar(c, 120)));
                }
            }
        }
        if (participantes.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agrega al menos un participante");
        if (participantes.size() > 8) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Máximo 8 participantes");

        String textoPregunta = "¿En qué estado está el bien?";
        List<String> opciones = new ArrayList<>(OPCIONES_DEFAULT);
        if (datos.get("pregunta") instanceof Map<?, ?> pq) {
            if (!texto(pq.get("texto")).isBlank()) textoPregunta = limitar(texto(pq.get("texto")), 160);
            if (pq.get("opciones") instanceof List<?> ops) {
                List<String> limpias = ops.stream().map(LevantamientoService::texto).map(x -> limitar(x, 40))
                        .filter(x -> !x.isBlank()).distinct().toList();
                if (limpias.size() >= 2) opciones = new ArrayList<>(limpias.subList(0, Math.min(6, limpias.size())));
            }
        }

        LocalDateTime inicio = LocalDateTime.now();
        Object fi = datos.get("fechaInicio");
        if (fi != null && !texto(fi).isBlank()) {
            try { inicio = LocalDateTime.parse(texto(fi).length() == 16 ? texto(fi) + ":00" : texto(fi)); }
            catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha de inicio no es válida"); }
        }

        Levantamiento l = new Levantamiento();
        l.setIdSede(idSede);
        l.setAnio(largo(datos.get("anio")) != null ? largo(datos.get("anio")).intValue() : inicio.getYear());
        l.setEstado("EN_CURSO");
        l.setCiudad(limitar(texto(datos.get("ciudad")), 150));
        l.setDireccion(limitar(texto(datos.get("direccion")), 300));
        l.setFechaInicio(inicio);
        l.setParticipantes(participantes);
        Map<String, Object> pregunta = new LinkedHashMap<>();
        pregunta.put("texto", textoPregunta);
        pregunta.put("opciones", opciones);
        l.setPregunta(pregunta);
        l.setIdUsuarioCapturista(capturista.getIdUsuario());
        l.setIdUsuarioCreo(u.getIdUsuario());
        levantamientoRepository.saveAndFlush(l);

        // Acta de inicio (se guarda en Documentos)
        Map<String, Object> acta = baseActa(l, sede, inicio);
        acta.put("tipo", "ACTA_INICIO");
        acta.put("titulo", "Acta de inicio · Inventario " + l.getAnio() + " · " + sede.getNombreSede());
        Documento doc = documentoFormatoService.registrarActa("ACTA_INICIO", idSede, acta, u, l.getAnio());
        l.setIdDocumentoInicio(doc.getIdDocumento());
        levantamientoRepository.save(l);
        return resumen(l, u);
    }

    // ===================== Captura =====================

    public Map<String, Object> registrar(Long id, Long idBien, String respuesta, Long idUbicacionArea,
                                         byte[] foto, String tipoFoto) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        if (!"EN_CURSO".equals(l.getEstado())) throw new ResponseStatusException(HttpStatus.CONFLICT, "El levantamiento ya está cerrado");
        if (!u.getIdUsuario().equals(l.getIdUsuarioCapturista()) && !puedeAdministrar(u, l.getIdSede())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el capturista asignado puede registrar bienes");
        }
        Bien bien = bienRepository.findByIdBienAndActivoTrue(idBien)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "El bien no existe o está dado de baja"));
        Long sedeBien = bien.getUbicacion() != null && bien.getUbicacion().getSede() != null ? bien.getUbicacion().getSede().getIdSede() : null;
        if (!l.getIdSede().equals(sedeBien)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este bien pertenece a otra sede (" +
                    (bien.getUbicacion() != null && bien.getUbicacion().getSede() != null ? bien.getUbicacion().getSede().getNombreSede() : "sin sede") + ")");
        }
        List<?> opciones = l.getPregunta() != null && l.getPregunta().get("opciones") instanceof List<?> o ? o : OPCIONES_DEFAULT;
        if (respuesta == null || opciones.stream().noneMatch(x -> String.valueOf(x).equals(respuesta))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige una respuesta");
        }

        boolean fotoNueva = false;
        if (bien.getFotoKey() == null) {
            if (foto == null || foto.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Este bien todavía no tiene foto: tómale una");
            }
            if (foto.length > 8 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La foto es demasiado grande");
            String tipo = tipoFoto != null && tipoFoto.startsWith("image/") ? tipoFoto : "image/jpeg";
            String llave = fotoStorage.guardar("bienes/" + bien.getIdBien() + "-" + bien.getCodigoQr().substring(0, 8), foto, tipo);
            bien.setFotoKey(llave);
            bien.setFotoFecha(LocalDateTime.now());
            bienRepository.save(bien);
            fotoNueva = true;
        }

        LevantamientoRegistro r = registroRepository.findByIdLevantamientoAndIdBien(id, idBien).orElseGet(LevantamientoRegistro::new);
        boolean yaEstaba = r.getIdRegistro() != null;
        r.setIdLevantamiento(id);
        r.setIdBien(idBien);
        r.setRespuesta(respuesta);
        r.setIdUbicacionEncontrada(idUbicacionArea);
        Long ubicacionRegistrada = bien.getUbicacion() != null ? bien.getUbicacion().getIdUbicacion() : null;
        r.setFueraDeLugar(idUbicacionArea != null && !idUbicacionArea.equals(ubicacionRegistrada));
        r.setFotoTomada(Boolean.TRUE.equals(r.getFotoTomada()) || fotoNueva);
        r.setIdUsuario(u.getIdUsuario());
        r.setFecha(LocalDateTime.now());
        registroRepository.save(r);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("actualizado", yaEstaba);
        resp.put("fueraDeLugar", r.getFueraDeLugar());
        resp.put("fotoNueva", fotoNueva);
        resp.put("foto_mini", fotoStorage.urlMini(bien.getFotoKey()));
        resp.put("foto_url", fotoStorage.urlGrande(bien.getFotoKey()));
        resp.put("capturados", registroRepository.countByIdLevantamiento(id));
        return resp;
    }

    // ===================== Eliminar =====================

    // Borra el levantamiento, sus registros, sus actas y las fotos que se tomaron en él
    // (en Cloudinary/R2/local). Los bienes cuya foto se borra vuelven a pedir foto.
    public Map<String, Object> eliminar(Long id) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        if (!puedeAdministrar(u, l.getIdSede())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el administrador de la sede puede eliminarlo");
        }
        List<LevantamientoRegistro> regs = registroRepository.findByIdLevantamiento(id);
        List<String> fotos = new ArrayList<>();
        for (LevantamientoRegistro r : regs) {
            if (!Boolean.TRUE.equals(r.getFotoTomada())) continue;
            bienRepository.findById(r.getIdBien()).ifPresent(b -> {
                if (b.getFotoKey() != null) {
                    fotos.add(b.getFotoKey());
                    b.setFotoKey(null);
                    b.setFotoFecha(null);
                    bienRepository.save(b);
                }
            });
        }
        registroRepository.deleteAll(regs);
        for (Long idDoc : Arrays.asList(l.getIdDocumentoInicio(), l.getIdDocumentoCierre())) {
            if (idDoc != null) documentoRepository.deleteById(idDoc);
        }
        levantamientoRepository.delete(l);

        int fallidas = 0;
        for (String k : fotos) if (!fotoStorage.borrar(k)) fallidas++;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("registros", regs.size());
        resp.put("fotosBorradas", fotos.size() - fallidas);
        resp.put("fotosNoBorradas", fallidas);
        return resp;
    }

    // ===================== Cerrar (Acta de cierre) =====================

    public Map<String, Object> cerrar(Long id) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        if (!puedeAdministrar(u, l.getIdSede())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el administrador de la sede puede cerrarlo");
        if (!"EN_CURSO".equals(l.getEstado())) throw new ResponseStatusException(HttpStatus.CONFLICT, "El levantamiento ya está cerrado");
        Sede sede = sedeRepository.findById(l.getIdSede()).orElseThrow();
        LocalDateTime cierre = LocalDateTime.now();

        Map<Long, LevantamientoRegistro> registros = registroRepository.findByIdLevantamiento(id).stream()
                .collect(Collectors.toMap(LevantamientoRegistro::getIdBien, r -> r, (a, b) -> a));
        Map<String, Integer> porClasificacion = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Integer> porRespuesta = new LinkedHashMap<>();
        List<Map<String, Object>> listado = new ArrayList<>();
        int encontrados = 0, fuera = 0, total = 0;
        for (Bien b : bienesDeSede(l.getIdSede())) {
            total++;
            LevantamientoRegistro r = registros.get(b.getIdBien());
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("clave", texto(b.getClave()));
            f.put("descripcion", unir(" · ", b.getArticulo(), b.getMarca() != null ? b.getMarca().getNombreMarca() : null));
            f.put("ubicacion", b.getUbicacion() != null ? texto(b.getUbicacion().getNombreArea()) : "");
            f.put("encontrado", r != null);
            f.put("respuesta", r != null ? texto(r.getRespuesta()) : "");
            f.put("fueraDeLugar", r != null && Boolean.TRUE.equals(r.getFueraDeLugar()));
            listado.add(f);
            if (r != null) {
                encontrados++;
                if (Boolean.TRUE.equals(r.getFueraDeLugar())) fuera++;
                String clas = b.getClasificacion() != null ? b.getClasificacion().getNombre() : "Sin clasificación";
                porClasificacion.merge(clas, 1, Integer::sum);
                porRespuesta.merge(texto(r.getRespuesta()), 1, Integer::sum);
            }
        }
        listado.sort(Comparator.comparing((Map<String, Object> f) -> !Boolean.TRUE.equals(f.get("encontrado")) ? 1 : 0)
                .thenComparing(f -> String.valueOf(f.get("ubicacion")))
                .thenComparing(f -> String.valueOf(f.get("clave"))));

        Map<String, Object> acta = baseActa(l, sede, cierre);
        acta.put("tipo", "ACTA_CIERRE");
        acta.put("titulo", "Acta de cierre · Inventario " + l.getAnio() + " · " + sede.getNombreSede());
        acta.put("resumen", porClasificacion.entrySet().stream()
                .map(e -> Map.<String, Object>of("clasificacion", e.getKey(), "total", e.getValue())).toList());
        Map<String, Object> totales = new LinkedHashMap<>();
        totales.put("bienes", total);
        totales.put("encontrados", encontrados);
        totales.put("noLocalizados", total - encontrados);
        totales.put("fueraDeLugar", fuera);
        totales.put("respuestas", porRespuesta);
        acta.put("totales", totales);
        acta.put("pregunta", l.getPregunta() != null ? l.getPregunta().get("texto") : "");
        acta.put("listado", listado);
        acta.put("fechaInicioTexto", fechaLarga(l.getFechaInicio()));

        Documento doc = documentoFormatoService.registrarActa("ACTA_CIERRE", l.getIdSede(), acta, u, l.getAnio());
        l.setEstado("CERRADO");
        l.setFechaCierre(cierre);
        l.setIdDocumentoCierre(doc.getIdDocumento());
        levantamientoRepository.save(l);
        return resumen(l, u);
    }

    // ===================== Foto del bien =====================

    @Transactional(readOnly = true)
    public Map<String, Object> foto(Long idBien) {
        Usuario u = usuarioActual();
        Bien b = bienRepository.findById(idBien).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bien no encontrado"));
        Long sedeBien = b.getUbicacion() != null && b.getUbicacion().getSede() != null ? b.getUbicacion().getSede().getIdSede() : null;
        boolean global = esAdmin(u) && u.getSedes().isEmpty();
        boolean deSuSede = sedeBien != null && u.getSedes().stream().anyMatch(s -> s.getIdSede().equals(sedeBien));
        if (!global && !deSuSede) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a este bien");
        if (b.getFotoKey() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "El bien no tiene foto");
        String k = b.getFotoKey();
        String tipo = k.endsWith(".png") ? "image/png" : k.endsWith(".webp") ? "image/webp" : "image/jpeg";
        return Map.of("bytes", fotoStorage.leer(k), "tipo", tipo);
    }

    // ===================== Apoyo =====================

    private Map<String, Object> baseActa(Levantamiento l, Sede sede, LocalDateTime fecha) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("sede", sede.getNombreSede());
        a.put("ciudad", texto(l.getCiudad()));
        a.put("direccion", texto(l.getDireccion()));
        a.put("anio", l.getAnio());
        a.put("dia", String.format("%02d", fecha.getDayOfMonth()));
        a.put("mes", fecha.format(DateTimeFormatter.ofPattern("MMMM", MX)));
        a.put("anioFecha", fecha.getYear());
        a.put("hora", fecha.format(DateTimeFormatter.ofPattern("HH:mm")));
        a.put("participantes", l.getParticipantes() == null ? List.of() : l.getParticipantes());
        a.put("idLevantamiento", l.getIdLevantamiento());
        return a;
    }

    private Map<String, Object> resumen(Levantamiento l, Usuario u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idLevantamiento", l.getIdLevantamiento());
        m.put("idSede", l.getIdSede());
        m.put("sede", sedeRepository.findById(l.getIdSede()).map(Sede::getNombreSede).orElse(""));
        m.put("anio", l.getAnio());
        m.put("estado", l.getEstado());
        m.put("ciudad", l.getCiudad());
        m.put("direccion", l.getDireccion());
        m.put("fechaInicio", l.getFechaInicio());
        m.put("fechaCierre", l.getFechaCierre());
        m.put("participantes", l.getParticipantes());
        m.put("pregunta", l.getPregunta());
        m.put("idUsuarioCapturista", l.getIdUsuarioCapturista());
        m.put("capturista", l.getIdUsuarioCapturista() == null ? "" :
                usuarioRepository.findById(l.getIdUsuarioCapturista()).map(LevantamientoService::nombre).orElse(""));
        m.put("idDocumentoInicio", l.getIdDocumentoInicio());
        m.put("idDocumentoCierre", l.getIdDocumentoCierre());
        m.put("totalBienes", bienesDeSede(l.getIdSede()).size());
        List<LevantamientoRegistro> regs = registroRepository.findByIdLevantamiento(l.getIdLevantamiento());
        m.put("capturados", regs.size());
        m.put("fueraDeLugar", regs.stream().filter(r -> Boolean.TRUE.equals(r.getFueraDeLugar())).count());
        m.put("fotosNuevas", regs.stream().filter(r -> Boolean.TRUE.equals(r.getFotoTomada())).count());
        m.put("soyCapturista", u.getIdUsuario().equals(l.getIdUsuarioCapturista()));
        m.put("puedoAdministrar", puedeAdministrar(u, l.getIdSede()));
        m.put("almacenFotos", fotoStorage.destino());
        return m;
    }

    private List<Bien> bienesDeSede(Long idSede) {
        return bienRepository.findByActivoTrueOrderByIdBienDesc().stream()
                .filter(b -> b.getUbicacion() != null && b.getUbicacion().getSede() != null
                        && idSede.equals(b.getUbicacion().getSede().getIdSede()))
                .toList();
    }

    private Levantamiento buscar(Long id) {
        return levantamientoRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Levantamiento no encontrado"));
    }

    private void validarVer(Usuario u, Levantamiento l) {
        if (!puedeAdministrar(u, l.getIdSede()) && !u.getIdUsuario().equals(l.getIdUsuarioCapturista())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a este levantamiento");
        }
    }

    // Admin sin sede asignada = general; admin con sedes = solo esas
    private boolean puedeAdministrar(Usuario u, Long idSede) {
        if (!esAdmin(u)) return false;
        return u.getSedes().isEmpty() || u.getSedes().stream().anyMatch(s -> s.getIdSede().equals(idSede));
    }

    private static boolean esAdmin(Usuario u) {
        return u.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getNombre()));
    }

    private Usuario usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        return usuarioRepository.findByCorreoIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }

    private static String nombre(Usuario u) {
        return u.getPersona() != null && u.getPersona().getNombreCompleto() != null ? u.getPersona().getNombreCompleto() : u.getCorreo();
    }

    private static String fechaLarga(LocalDateTime f) {
        return f == null ? "" : f.format(DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy, HH:mm 'hrs'", MX));
    }

    private static String texto(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    private static String limitar(String v, int max) { return v.length() > max ? v.substring(0, max) : v; }

    private static Long largo(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        try { return Long.valueOf(String.valueOf(v).trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String unir(String sep, String... partes) {
        return Arrays.stream(partes).filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(sep));
    }
}

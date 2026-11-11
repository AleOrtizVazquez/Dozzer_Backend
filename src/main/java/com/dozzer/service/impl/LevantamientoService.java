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
    @Autowired private PersonaRepository personaRepository;
    // TEMPORAL para pruebas: borrar inventarios cerrados (se apaga con PERMITIR_BORRAR_CERRADOS=false)
    @org.springframework.beans.factory.annotation.Value("${app.levantamientos.permitir-borrar-cerrados:false}")
    private boolean permitirBorrarCerrados;
    @Autowired private ClasificacionRepository clasificacionRepository;
    @Autowired private MarcaRepository marcaRepository;

    // Estatus de cada bien dentro del levantamiento
    public static final String PENDIENTE = "PENDIENTE";
    public static final String REVISADO = "REVISADO";
    public static final String NO_ENCONTRADO = "NO_ENCONTRADO";

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
        for (Bien b : bienesDeSede(l.getIdSede())) lista.add(itemBien(b, registros.get(b.getIdBien()), areas));
        return lista;
    }

    // Un bien como lo usan la captura y la lista de avance
    private Map<String, Object> itemBien(Bien b, LevantamientoRegistro r, Map<Long, String> areas) {
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
        m.put("sede", b.getUbicacion() != null && b.getUbicacion().getSede() != null ? b.getUbicacion().getSede().getNombreSede() : null);
        m.put("responsable", b.getResponsableArea() != null ? b.getResponsableArea().getNombreCompleto() : null);
        // "Persona" del levantamiento = usuario directo del bien (de quien es el resguardo)
        m.put("id_persona", b.getUsuarioDirecto() != null ? b.getUsuarioDirecto().getIdPersona() : null);
        m.put("persona", b.getUsuarioDirecto() != null ? b.getUsuarioDirecto().getNombreCompleto() : null);
        m.put("codigo_qr", b.getCodigoQr());
        m.put("etiqueta", b.getEtiqueta());
        m.put("tiene_foto", b.getFotoKey() != null);
        m.put("foto_mini", fotoStorage.urlMini(b.getFotoKey()));
        m.put("foto_url", fotoStorage.urlGrande(b.getFotoKey()));
        if (r != null) {
            boolean pendiente = PENDIENTE.equals(r.getEstatus());
            if (!pendiente) {
                m.put("estatus", r.getEstatus());
                m.put("fecha_registro", r.getFecha());
            }
            m.put("origen", r.getOrigen());
            m.put("sede_anterior", r.getSedeAnterior());
            if (REVISADO.equals(r.getEstatus())) {
                m.put("respuesta", r.getRespuesta());
                m.put("fuera_de_lugar", Boolean.TRUE.equals(r.getFueraDeLugar()));
                m.put("ubicacion_encontrada", areas == null ? null : areas.get(r.getIdUbicacionEncontrada()));
            }
        }
        return m;
    }

    // Areas, personas y catalogos de la sede (para "bien nuevo" y "traer a esta sede")
    @Transactional(readOnly = true)
    public Map<String, Object> opciones(Long id) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        validarVer(u, l);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("areas", ubicacionRepository.findBySede_IdSede(l.getIdSede()).stream()
                .filter(x -> !Boolean.FALSE.equals(x.getEstatus()))
                .sorted(Comparator.comparing(x -> texto(x.getNombreArea()), String.CASE_INSENSITIVE_ORDER))
                .map(x -> Map.<String, Object>of("id", x.getIdUbicacion(), "nombre", texto(x.getNombreArea()))).toList());
        r.put("personas", personaRepository.findBySede_IdSedeAndEstatusTrue(l.getIdSede()).stream()
                .sorted(Comparator.comparing(x -> texto(x.getNombreCompleto()), String.CASE_INSENSITIVE_ORDER))
                .map(x -> Map.<String, Object>of("id", x.getIdPersona(), "nombre", texto(x.getNombreCompleto()),
                        "numero", texto(x.getNumeroEmpleado()))).toList());
        r.put("clasificaciones", clasificacionRepository.findAllByOrderByNombreAsc().stream()
                .map(x -> Map.<String, Object>of("id", x.getIdClasificacion(), "nombre", texto(x.getNombre()))).toList());
        r.put("marcas", marcaRepository.findAllByOrderByNombreMarcaAsc().stream()
                .map(x -> Map.<String, Object>of("id", x.getIdMarca(), "nombre", texto(x.getNombreMarca()))).toList());
        return r;
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
        Long per = largo(datos.get("periodo"));
        l.setPeriodo(per != null && (per == 1 || per == 2) ? per.intValue() : (inicio.getMonthValue() <= 6 ? 1 : 2));
        // Candado: un inventario por sede, año y semestre
        if (levantamientoRepository.existsByIdSedeAndAnioAndPeriodo(idSede, l.getAnio(), l.getPeriodo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe el " + nombreInventario(l) + " de " + sede.getNombreSede()
                    + ". Elige el otro semestre o, si está en curso y fue una prueba, elimínalo primero.");
        }
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
        acta.put("idLevantamiento", l.getIdLevantamiento());
        acta.put("titulo", "Acta de inicio · " + nombreInventario(l) + " · " + sede.getNombreSede());
        Documento doc = documentoFormatoService.registrarActa("ACTA_INICIO", idSede, acta, u, l.getAnio());
        l.setIdDocumentoInicio(doc.getIdDocumento());
        levantamientoRepository.save(l);
        asegurarActaBorrador(l, u);
        return resumen(l, u);
    }

    // ===================== Captura =====================

    public Map<String, Object> registrar(Long id, Long idBien, String respuesta, Long idUbicacionArea,
                                         byte[] foto, String tipoFoto) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        validarCaptura(u, l);
        Bien bien = bienDeLaSede(l, idBien);
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
        r.setEstatus(REVISADO);
        r.setIdUsuario(u.getIdUsuario());
        r.setFecha(LocalDateTime.now());
        registroRepository.saveAndFlush(r);
        actualizarResguardos(l, Collections.singleton(idPersona(bien)), u);
        asegurarActaBorrador(l, u);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("actualizado", yaEstaba);
        resp.put("fueraDeLugar", r.getFueraDeLugar());
        resp.put("fotoNueva", fotoNueva);
        resp.put("foto_mini", fotoStorage.urlMini(bien.getFotoKey()));
        resp.put("foto_url", fotoStorage.urlGrande(bien.getFotoKey()));
        resp.put("capturados", contar(registroRepository.findByIdLevantamiento(id), REVISADO));
        return resp;
    }

    // ===================== No encontrado =====================

    // Palomita "No encontrado": el bien esta en el sistema pero no aparecio fisicamente
    public Map<String, Object> marcarNoEncontrado(Long id, Long idBien) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        validarCaptura(u, l);
        Bien bien = bienDeLaSede(l, idBien);
        LevantamientoRegistro r = registroRepository.findByIdLevantamientoAndIdBien(id, idBien).orElseGet(LevantamientoRegistro::new);
        r.setIdLevantamiento(id);
        r.setIdBien(idBien);
        r.setEstatus(NO_ENCONTRADO);
        r.setRespuesta(null);
        r.setIdUbicacionEncontrada(null);
        r.setFueraDeLugar(false);
        r.setFotoTomada(Boolean.TRUE.equals(r.getFotoTomada()));
        r.setIdUsuario(u.getIdUsuario());
        r.setFecha(LocalDateTime.now());
        registroRepository.saveAndFlush(r);
        actualizarResguardos(l, Collections.singleton(idPersona(bien)), u);
        asegurarActaBorrador(l, u);
        return itemBien(bien, r, null);
    }

    // ===================== Buscar en todo el inventario =====================

    // Busca en TODAS las sedes (clave, serie, articulo, modelo, marca o el ID del QR)
    @Transactional(readOnly = true)
    public List<Map<String, Object>> buscarGeneral(Long id, String q) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        validarVer(u, l);
        String t = texto(q).toLowerCase(MX);
        if (t.length() < 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe al menos 2 letras o números");
        String idQr = t.replace("-", "").toUpperCase(MX);
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Bien b : bienRepository.findByActivoTrueOrderByIdBienDesc()) {
            boolean coincide = texto(b.getClave()).toLowerCase(MX).contains(t)
                    || texto(b.getNumeroSerie()).toLowerCase(MX).contains(t)
                    || texto(b.getArticulo()).toLowerCase(MX).contains(t)
                    || texto(b.getModelo()).toLowerCase(MX).contains(t)
                    || (b.getMarca() != null && texto(b.getMarca().getNombreMarca()).toLowerCase(MX).contains(t))
                    || (idQr.length() == 8 && texto(b.getCodigoQr()).replace("-", "").toUpperCase(MX).startsWith(idQr));
            if (!coincide) continue;
            Map<String, Object> m = itemBien(b, null, null);
            Long sedeBien = idSedeDe(b);
            m.put("id_sede", sedeBien);
            m.put("en_esta_sede", l.getIdSede().equals(sedeBien));
            lista.add(m);
            if (lista.size() >= 25) break;
        }
        return lista;
    }

    // ===================== Traer de otra sede =====================

    // El bien aparecio aqui pero el sistema lo tiene en otra sede: se mueve a esta (area y persona)
    public Map<String, Object> traer(Long id, Long idBien, Long idUbicacion, Long idPersonaNueva) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        validarCaptura(u, l);
        Bien bien = bienRepository.findByIdBienAndActivoTrue(idBien)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "El bien no existe o está dado de baja"));
        Long sedeAnterior = idSedeDe(bien);
        if (l.getIdSede().equals(sedeAnterior)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Este bien ya es de esta sede");
        String nombreSedeAnterior = bien.getUbicacion() != null && bien.getUbicacion().getSede() != null
                ? bien.getUbicacion().getSede().getNombreSede() : "Sin sede";
        Long personaAnterior = idPersona(bien);

        bien.setUbicacion(areaDeLaSede(l, idUbicacion));
        if (idPersonaNueva != null) bien.setUsuarioDirecto(personaActiva(idPersonaNueva));
        bienRepository.saveAndFlush(bien);

        // Si la otra sede tiene un levantamiento abierto, ahi deja de contarse
        for (Levantamiento otro : levantamientoRepository.findAllByOrderByIdLevantamientoDesc()) {
            if (otro.getIdLevantamiento().equals(id) || !"EN_CURSO".equals(otro.getEstado())) continue;
            Optional<LevantamientoRegistro> x = registroRepository.findByIdLevantamientoAndIdBien(otro.getIdLevantamiento(), idBien);
            if (x.isPresent()) {
                registroRepository.delete(x.get());
                registroRepository.flush();
                actualizarResguardos(otro, Collections.singleton(personaAnterior), u);
            }
        }

        LevantamientoRegistro r = registroRepository.findByIdLevantamientoAndIdBien(id, idBien).orElseGet(LevantamientoRegistro::new);
        if (r.getIdRegistro() == null) r.setEstatus(PENDIENTE);
        r.setIdLevantamiento(id);
        r.setIdBien(idBien);
        r.setOrigen("TRANSFERIDO");
        r.setSedeAnterior(limitar(nombreSedeAnterior, 150));
        r.setIdUsuario(u.getIdUsuario());
        r.setFecha(LocalDateTime.now());
        registroRepository.saveAndFlush(r);
        Set<Long> afectadas = new HashSet<>();
        afectadas.add(personaAnterior);
        afectadas.add(idPersona(bien));
        actualizarResguardos(l, afectadas, u);
        asegurarActaBorrador(l, u);
        return itemBien(bien, r, null);
    }

    // ===================== Bien nuevo =====================

    // Bien que no estaba en el sistema: entra directo (sin autorizacion) y queda pendiente de revisar
    public Map<String, Object> nuevoBien(Long id, Map<String, Object> datos) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        validarCaptura(u, l);
        String articulo = limitar(texto(datos.get("articulo")), 200);
        if (articulo.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe qué es el bien");
        Ubicacion area = areaDeLaSede(l, largo(datos.get("idUbicacion")));
        Sede sede = sedeRepository.findById(l.getIdSede()).orElseThrow();

        String clave = limitar(texto(datos.get("clave")).toUpperCase(MX), 50);
        if (clave.isBlank()) clave = siguienteClave(sede);
        else if (bienRepository.existsByClave(clave)) throw new ResponseStatusException(HttpStatus.CONFLICT, "La clave " + clave + " ya existe");

        Bien b = new Bien();
        b.setClave(clave);
        b.setArticulo(articulo);
        b.setCaracteristicas(vacioANull(limitar(texto(datos.get("caracteristicas")), 500)));
        b.setModelo(vacioANull(limitar(texto(datos.get("modelo")), 150)));
        b.setNumeroSerie(vacioANull(limitar(texto(datos.get("numeroSerie")), 150)));
        Long idClas = largo(datos.get("idClasificacion"));
        if (idClas != null) b.setClasificacion(clasificacionRepository.findById(idClas).orElse(null));
        Long idMarca = largo(datos.get("idMarca"));
        if (idMarca != null) b.setMarca(marcaRepository.findById(idMarca).orElse(null));
        Long idPer = largo(datos.get("idPersona"));
        if (idPer != null) b.setUsuarioDirecto(personaActiva(idPer));
        b.setUbicacion(area);
        b.setCantidadPiezas(1);
        b.setUsuarioRegistro(u);
        bienRepository.saveAndFlush(b);

        LevantamientoRegistro r = new LevantamientoRegistro();
        r.setIdLevantamiento(id);
        r.setIdBien(b.getIdBien());
        r.setEstatus(PENDIENTE);
        r.setOrigen("NUEVO");
        r.setIdUsuario(u.getIdUsuario());
        r.setFecha(LocalDateTime.now());
        registroRepository.saveAndFlush(r);

        // Formato de alta, igual que un alta directa del administrador
        Documento alta = documentoFormatoService.registrar("ALTA", b, null, u, u,
                "Alta registrada durante el inventario " + l.getAnio() + " de la sede " + sede.getNombreSede() + ".");
        asegurarActaBorrador(l, u);
        Map<String, Object> m = itemBien(b, r, null);
        m.put("idDocumentoAlta", alta.getIdDocumento());
        return m;
    }

    // Prefijo de la sede + el numero siguiente (ej. CE1839)
    private String siguienteClave(Sede sede) {
        String prefijo = texto(sede.getPrefijoClave()).toUpperCase(MX);
        if (prefijo.isBlank()) prefijo = "B";
        int max = 0, digitos = 4;
        for (Bien x : bienRepository.findByClaveStartingWithIgnoreCase(prefijo)) {
            String resto = texto(x.getClave()).substring(prefijo.length());
            if (!resto.matches("[0-9]{1,9}")) continue;
            max = Math.max(max, Integer.parseInt(resto));
            digitos = Math.max(digitos, resto.length());
        }
        String clave;
        do { clave = prefijo + String.format("%0" + digitos + "d", ++max); } while (bienRepository.existsByClave(clave));
        return clave;
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
        boolean cerrado = "CERRADO".equals(l.getEstado());
        if (cerrado && !permitirBorrarCerrados) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un inventario cerrado ya es oficial (actas y resguardos firmados) y no se puede eliminar");
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
        // Actas, resguardos y borradores de este levantamiento (los bienes nuevos y sus altas se quedan)
        List<Documento> docs = new ArrayList<>(documentoRepository.findByIdLevantamiento(id));
        for (Long idDoc : Arrays.asList(l.getIdDocumentoInicio(), l.getIdDocumentoCierre())) {
            if (idDoc != null && docs.stream().noneMatch(d -> idDoc.equals(d.getIdDocumento())))
                documentoRepository.findById(idDoc).ifPresent(docs::add);
        }
        // Personas cuyo resguardo de este inventario se borra: su resguardo anterior vuelve a quedar vigente
        Set<Long> personasResg = docs.stream().filter(d -> "RESGUARDO".equals(d.getTipo()) && d.getIdPersona() != null)
                .map(Documento::getIdPersona).collect(Collectors.toSet());
        documentoRepository.deleteAll(docs);
        documentoRepository.flush();
        for (Long idPer : personasResg) {
            if (!documentoRepository.findByTipoAndIdPersonaAndEstado("RESGUARDO", idPer, "VIGENTE").isEmpty()) continue;
            documentoRepository.findByTipoAndIdPersonaAndEstado("RESGUARDO", idPer, "HISTORICO").stream()
                    .max(Comparator.comparing(Documento::getIdDocumento))
                    .ifPresent(d -> { d.setEstado("VIGENTE"); documentoRepository.save(d); });
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

    // Lo que se revisa antes de cerrar: pendientes y quién falta de firmar su resguardo
    @Transactional(readOnly = true)
    public Map<String, Object> resumenCierre(Long id) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        validarVer(u, l);
        List<LevantamientoRegistro> regs = registroRepository.findByIdLevantamiento(id);
        Map<Long, LevantamientoRegistro> porBien = regs.stream().collect(Collectors.toMap(LevantamientoRegistro::getIdBien, r -> r, (a, b) -> a));
        int total = 0, revisados = 0, noEnc = 0;
        Set<Long> personasConRevisados = new HashSet<>();
        for (Bien b : bienesDeSede(l.getIdSede())) {
            total++;
            LevantamientoRegistro r = porBien.get(b.getIdBien());
            if (r != null && REVISADO.equals(r.getEstatus())) { revisados++; if (idPersona(b) != null) personasConRevisados.add(idPersona(b)); }
            if (r != null && NO_ENCONTRADO.equals(r.getEstatus())) noEnc++;
        }
        Set<Long> firmadas = new HashSet<>();
        for (Documento d : documentoRepository.findByIdLevantamiento(id)) {
            if ("RESGUARDO".equals(d.getTipo()) && d.getIdPersona() != null && d.getDatos() != null && d.getDatos().get("firma") != null) firmadas.add(d.getIdPersona());
        }
        List<String> sinFirma = personaRepository.findAllById(personasConRevisados).stream()
                .filter(p -> !firmadas.contains(p.getIdPersona())).map(p -> texto(p.getNombreCompleto())).sorted().toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", total);
        r.put("revisados", revisados);
        r.put("noEncontrados", noEnc);
        r.put("pendientes", total - revisados - noEnc);
        r.put("personas", personasConRevisados.size());
        r.put("firmadas", personasConRevisados.stream().filter(firmadas::contains).count());
        r.put("sinFirma", sinFirma);
        r.put("participantes", l.getParticipantes() == null ? List.of() : l.getParticipantes());
        return r;
    }

    public Map<String, Object> cerrar(Long id) {
        return cerrar(id, Map.of());
    }

    public Map<String, Object> cerrar(Long id, Map<String, Object> datos) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        if (!puedeAdministrar(u, l.getIdSede())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el administrador de la sede puede cerrarlo");
        if (!"EN_CURSO".equals(l.getEstado())) throw new ResponseStatusException(HttpStatus.CONFLICT, "El levantamiento ya está cerrado");
        Sede sede = sedeRepository.findById(l.getIdSede()).orElseThrow();
        LocalDateTime cierre = LocalDateTime.now();
        Map<String, Object> acta = construirActaCierre(l, sede, cierre);
        // Firmas de los participantes (en el mismo orden); quien no firma en pantalla firma en papel
        if (datos.get("firmas") instanceof List<?> firmas && acta.get("participantes") instanceof List<?> ps) {
            List<Map<String, Object>> conFirma = new ArrayList<>();
            for (int i = 0; i < ps.size(); i++) {
                Map<String, Object> p = new LinkedHashMap<>();
                if (ps.get(i) instanceof Map<?, ?> m) m.forEach((k, v) -> p.put(String.valueOf(k), v));
                String f = i < firmas.size() ? firmaValida(firmas.get(i), false, "") : null;
                if (f != null) p.put("firma", f);
                conFirma.add(p);
            }
            acta.put("participantes", conFirma);
            acta.put("firmadoEnPantalla", conFirma.stream().anyMatch(p -> p.get("firma") != null));
        }

        // El borrador del acta se vuelve definitivo (mismo folio); si no habia, se crea
        Documento doc = l.getIdDocumentoCierre() == null ? null : documentoRepository.findById(l.getIdDocumentoCierre()).orElse(null);
        if (doc != null) {
            acta.put("folio", doc.getFolio());
            doc.setDatos(acta);
            doc.setEstado("VIGENTE");
            doc.setFechaCreacion(cierre);
            documentoRepository.save(doc);
        } else {
            doc = documentoFormatoService.registrarActa("ACTA_CIERRE", l.getIdSede(), acta, u, l.getAnio());
        }
        l.setEstado("CERRADO");
        l.setFechaCierre(cierre);
        l.setIdDocumentoCierre(doc.getIdDocumento());
        levantamientoRepository.save(l);

        // Resguardos: quedan emitidos (vigentes) y los anteriores de cada persona pasan a historico
        actualizarResguardos(l, null, u);
        return resumen(l, u);
    }

    // Acta de cierre al momento (borrador que se ve en Documentos mientras el inventario sigue)
    @Transactional(readOnly = true)
    public Map<String, Object> datosActaCierre(Long idLevantamiento) {
        Levantamiento l = buscar(idLevantamiento);
        Sede sede = sedeRepository.findById(l.getIdSede()).orElseThrow();
        return construirActaCierre(l, sede, LocalDateTime.now());
    }

    private Map<String, Object> construirActaCierre(Levantamiento l, Sede sede, LocalDateTime cierre) {
        List<LevantamientoRegistro> regs = registroRepository.findByIdLevantamiento(l.getIdLevantamiento());
        Map<Long, LevantamientoRegistro> registros = regs.stream()
                .filter(r -> REVISADO.equals(r.getEstatus()))
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
        acta.put("titulo", "Acta de cierre · " + nombreInventario(l) + " · " + sede.getNombreSede());
        acta.put("resumen", porClasificacion.entrySet().stream()
                .map(e -> Map.<String, Object>of("clasificacion", e.getKey(), "total", e.getValue())).toList());
        Map<String, Object> totales = new LinkedHashMap<>();
        totales.put("bienes", total);
        totales.put("encontrados", encontrados);
        totales.put("noLocalizados", total - encontrados);
        totales.put("fueraDeLugar", fuera);
        totales.put("respuestas", porRespuesta);
        totales.put("marcadosNoEncontrados", contar(regs, NO_ENCONTRADO));
        totales.put("nuevos", regs.stream().filter(r -> "NUEVO".equals(r.getOrigen())).count());
        totales.put("transferidos", regs.stream().filter(r -> "TRANSFERIDO".equals(r.getOrigen())).count());
        acta.put("totales", totales);
        acta.put("pregunta", l.getPregunta() != null ? l.getPregunta().get("texto") : "");
        acta.put("listado", listado);
        acta.put("fechaInicioTexto", fechaLarga(l.getFechaInicio()));
        return acta;
    }

    // ===================== Resguardos individuales =====================

    // Arma (o actualiza) el resguardo de cada persona con los bienes REVISADOS a su cargo.
    // personas = null -> todas. En curso queda como BORRADOR; al cerrar, VIGENTE.
    private void actualizarResguardos(Levantamiento l, Set<Long> personas, Usuario u) {
        if (personas != null) {
            personas = personas.stream().filter(Objects::nonNull).collect(Collectors.toSet());
            if (personas.isEmpty()) return;
        }
        boolean cerrado = "CERRADO".equals(l.getEstado());
        Map<Long, LevantamientoRegistro> revisados = registroRepository.findByIdLevantamiento(l.getIdLevantamiento()).stream()
                .filter(r -> REVISADO.equals(r.getEstatus()))
                .collect(Collectors.toMap(LevantamientoRegistro::getIdBien, r -> r, (a, b) -> a));
        Map<Long, List<Bien>> porPersona = new HashMap<>();
        for (Bien b : bienRepository.findAllById(revisados.keySet())) {
            Long p = idPersona(b);
            if (p == null || !Boolean.TRUE.equals(b.getActivo()) || !l.getIdSede().equals(idSedeDe(b))) continue;
            if (personas != null && !personas.contains(p)) continue;
            porPersona.computeIfAbsent(p, k -> new ArrayList<>()).add(b);
        }
        Set<Long> aRevisar = personas != null ? new HashSet<>(personas) : new HashSet<>(porPersona.keySet());
        if (personas == null) {
            // Tambien los que ya existian y se quedaron sin bienes
            documentoRepository.findByIdLevantamiento(l.getIdLevantamiento()).stream()
                    .filter(d -> "RESGUARDO".equals(d.getTipo()) && d.getIdPersona() != null)
                    .forEach(d -> aRevisar.add(d.getIdPersona()));
        }
        Sede sede = sedeRepository.findById(l.getIdSede()).orElseThrow();
        for (Long idPer : aRevisar) {
            List<Bien> bienes = porPersona.getOrDefault(idPer, List.of());
            Optional<Documento> existente = documentoRepository.findFirstByIdLevantamientoAndTipoAndIdPersona(l.getIdLevantamiento(), "RESGUARDO", idPer);
            if (bienes.isEmpty()) {
                existente.ifPresent(documentoRepository::delete);
                continue;
            }
            Persona persona = personaRepository.findById(idPer).orElse(null);
            if (persona == null) continue;
            Map<String, Object> datos = construirResguardo(l, sede, persona, bienes, revisados);
            Documento doc;
            boolean firmado = false;
            if (existente.isPresent()) {
                doc = existente.get();
                Map<String, Object> previo = doc.getDatos() == null ? Map.of() : doc.getDatos();
                datos.put("folio", doc.getFolio());
                // La firma sigue valiendo solo si la lista de bienes y sus estados no cambiaron
                if (previo.get("firma") != null) {
                    if (Objects.equals(previo.get("huella"), datos.get("huella"))) {
                        datos.put("firma", previo.get("firma"));
                        firmado = true;
                    } else {
                        datos.put("requiereNuevaFirma", true);
                    }
                }
                doc.setDatos(datos);
                doc.setEstado(cerrado || firmado ? "VIGENTE" : "BORRADOR");
                if (cerrado && !firmado) doc.setFechaCreacion(LocalDateTime.now());
                documentoRepository.save(doc);
            } else {
                doc = documentoFormatoService.registrarDocumentoInventario("RESGUARDO", l.getIdSede(), l.getIdLevantamiento(),
                        idPer, datos, u, l.getAnio(), cerrado ? "VIGENTE" : "BORRADOR");
            }
            if (cerrado || firmado) {
                // El resguardo anterior de la persona queda como historico
                for (Documento viejo : documentoRepository.findByTipoAndIdPersonaAndEstado("RESGUARDO", idPer, "VIGENTE")) {
                    if (!viejo.getIdDocumento().equals(doc.getIdDocumento())) {
                        viejo.setEstado("HISTORICO");
                        documentoRepository.save(viejo);
                    }
                }
            }
        }
    }

    // Datos del formato FA-04-02-02 "Resguardo de Inventarios"
    private Map<String, Object> construirResguardo(Levantamiento l, Sede sede, Persona p, List<Bien> bienes,
                                                   Map<Long, LevantamientoRegistro> registros) {
        LocalDateTime hoy = LocalDateTime.now();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("tipo", "RESGUARDO");
        d.put("folio", "");
        d.put("titulo", "Resguardo · " + texto(p.getNombreCompleto()));
        d.put("formato", "FA-04-02-02");
        d.put("revision", "2");
        d.put("sede", sede.getNombreSede());
        d.put("anio", l.getAnio());
        d.put("levantamiento", nombreInventario(l) + " · " + sede.getNombreSede());
        d.put("idLevantamiento", l.getIdLevantamiento());
        d.put("fecha", hoy.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        Map<String, Object> persona = new LinkedHashMap<>();
        persona.put("nombre", texto(p.getNombreCompleto()));
        persona.put("numeroEmpleado", texto(p.getNumeroEmpleado()));
        persona.put("puesto", p.getPuesto() != null ? texto(p.getPuesto().getNombrePuesto()) : "");
        persona.put("area", p.getPuesto() != null && p.getPuesto().getDepartamento() != null
                ? texto(p.getPuesto().getDepartamento().getNombreDepartamento()) : "");
        d.put("persona", persona);
        List<Map<String, Object>> filas = new ArrayList<>();
        bienes.stream().sorted(Comparator.comparing(b -> texto(b.getClave()), String.CASE_INSENSITIVE_ORDER)).forEach(b -> {
            LevantamientoRegistro r = registros.get(b.getIdBien());
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("nombre", texto(b.getArticulo()));
            f.put("descripcion", unir(" · ", b.getCaracteristicas(), b.getMarca() != null ? b.getMarca().getNombreMarca() : null));
            f.put("clave", texto(b.getClave()));
            String ms = unir(" / ", b.getModelo(), b.getNumeroSerie());
            f.put("modeloSerie", ms.isBlank() ? "SIN MODELO" : ms);
            f.put("area", b.getUbicacion() != null ? texto(b.getUbicacion().getNombreArea()) : "");
            f.put("piezas", b.getCantidadPiezas() != null ? b.getCantidadPiezas() : 1);
            f.put("estado", r != null ? texto(r.getRespuesta()) : "");
            f.put("origen", r != null ? r.getOrigen() : null);
            f.put("sedeAnterior", r != null ? r.getSedeAnterior() : null);
            filas.add(f);
        });
        d.put("filas", filas);
        d.put("totalBienes", filas.size());
        // Huella del contenido: si cambia, la firma deja de valer
        d.put("huella", filas.stream().map(f -> f.get("clave") + "|" + f.get("estado")).collect(Collectors.joining(";")));
        d.put("recursosNombre", "Juan Carlos Zuno Muñiz");
        d.put("recursosCargo", "Jefe de recursos materiales");
        return d;
    }

    // Estado de cada persona en el levantamiento (para marcar quién ya firmó)
    @Transactional(readOnly = true)
    public Map<String, Object> estadoPersonas(Long id) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        validarVer(u, l);
        Map<String, Object> r = new LinkedHashMap<>();
        for (Documento d : documentoRepository.findByIdLevantamiento(id)) {
            if (!"RESGUARDO".equals(d.getTipo()) || d.getIdPersona() == null) continue;
            Map<String, Object> datos = d.getDatos() == null ? Map.of() : d.getDatos();
            String estado;
            if (datos.get("firma") instanceof Map<?, ?> f) estado = Boolean.FALSE.equals(f.get("conforme")) ? "CON_DIFERENCIAS" : "FIRMADO";
            else estado = Boolean.TRUE.equals(datos.get("requiereNuevaFirma")) ? "REQUIERE_FIRMA" : "EN_PROCESO";
            r.put(String.valueOf(d.getIdPersona()), Map.of("estado", estado, "idDocumento", d.getIdDocumento()));
        }
        return r;
    }

    // "Terminar con esta persona": los pendientes pueden quedar como no encontrados, se registra si está
    // de acuerdo (o las diferencias) y se guardan las firmas del resguardante y del responsable.
    public Map<String, Object> terminarPersona(Long id, Long idPersona, Map<String, Object> datos) {
        Usuario u = usuarioActual();
        Levantamiento l = buscar(id);
        validarCaptura(u, l);
        if (idPersona == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica la persona");
        boolean conforme = !Boolean.FALSE.equals(datos.get("conforme"));
        String observaciones = limitar(texto(datos.get("observaciones")), 1000);
        if (!conforme && observaciones.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe cuáles son las diferencias");
        }
        String firmaResguardante = firmaValida(datos.get("firmaResguardante"), conforme, "Falta la firma de la persona");
        String firmaResponsable = firmaValida(datos.get("firmaResponsable"), true, "Falta la firma del responsable del inventario");

        // Pendientes de la persona → no encontrados (si así se pidió)
        if (Boolean.TRUE.equals(datos.get("marcarPendientes"))) {
            Map<Long, LevantamientoRegistro> regs = registroRepository.findByIdLevantamiento(id).stream()
                    .collect(Collectors.toMap(LevantamientoRegistro::getIdBien, r -> r, (a, b) -> a));
            for (Bien b : bienesDeSede(l.getIdSede())) {
                if (!idPersona.equals(idPersona(b))) continue;
                LevantamientoRegistro r = regs.get(b.getIdBien());
                if (r != null && !PENDIENTE.equals(r.getEstatus())) continue;
                if (r == null) { r = new LevantamientoRegistro(); r.setIdLevantamiento(id); r.setIdBien(b.getIdBien()); r.setFotoTomada(false); }
                r.setEstatus(NO_ENCONTRADO);
                r.setRespuesta(null);
                r.setFueraDeLugar(false);
                r.setIdUsuario(u.getIdUsuario());
                r.setFecha(LocalDateTime.now());
                registroRepository.save(r);
            }
            registroRepository.flush();
        }
        actualizarResguardos(l, Collections.singleton(idPersona), u);
        Documento doc = documentoRepository.findFirstByIdLevantamientoAndTipoAndIdPersona(id, "RESGUARDO", idPersona)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Esta persona no tiene bienes revisados: no hay resguardo que firmar"));

        Map<String, Object> firma = new LinkedHashMap<>();
        firma.put("conforme", conforme);
        firma.put("observaciones", observaciones);
        firma.put("resguardante", firmaResguardante);
        firma.put("responsable", firmaResponsable);
        firma.put("responsableNombre", nombre(u));
        firma.put("fecha", LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        Map<String, Object> d = new LinkedHashMap<>(doc.getDatos());
        d.put("firma", firma);
        d.remove("requiereNuevaFirma");
        doc.setDatos(d);
        doc.setEstado("VIGENTE");
        documentoRepository.save(doc);
        for (Documento viejo : documentoRepository.findByTipoAndIdPersonaAndEstado("RESGUARDO", idPersona, "VIGENTE")) {
            if (!viejo.getIdDocumento().equals(doc.getIdDocumento())) { viejo.setEstado("HISTORICO"); documentoRepository.save(viejo); }
        }
        asegurarActaBorrador(l, u);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("idDocumento", doc.getIdDocumento());
        r.put("estado", conforme ? "FIRMADO" : "CON_DIFERENCIAS");
        return r;
    }

    // Firma dibujada en pantalla: imagen PNG en base64 de tamaño razonable
    private static String firmaValida(Object v, boolean obligatoria, String mensaje) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.isBlank()) {
            if (obligatoria) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, mensaje);
            return null;
        }
        if (!s.startsWith("data:image/png;base64,") || s.length() > 200_000 || !s.substring(22).matches("[A-Za-z0-9+/=]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La firma no es válida; vuelve a dibujarla");
        }
        return s;
    }

    // Acta de cierre en BORRADOR desde que empieza el levantamiento (sus datos se arman al abrirla)
    private void asegurarActaBorrador(Levantamiento l, Usuario u) {
        if (l.getIdDocumentoCierre() != null || !"EN_CURSO".equals(l.getEstado())) return;
        Sede sede = sedeRepository.findById(l.getIdSede()).orElseThrow();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("tipo", "ACTA_CIERRE");
        d.put("titulo", "Acta de cierre · " + nombreInventario(l) + " · " + sede.getNombreSede());
        d.put("sede", sede.getNombreSede());
        d.put("anio", l.getAnio());
        d.put("idLevantamiento", l.getIdLevantamiento());
        Documento doc = documentoFormatoService.registrarDocumentoInventario("ACTA_CIERRE", l.getIdSede(), l.getIdLevantamiento(),
                null, d, u, l.getAnio(), "BORRADOR");
        l.setIdDocumentoCierre(doc.getIdDocumento());
        levantamientoRepository.save(l);
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
        a.put("periodo", l.getPeriodo());
        a.put("periodoTexto", textoPeriodo(l.getPeriodo()));
        a.put("idLevantamiento", l.getIdLevantamiento());
        return a;
    }

    private Map<String, Object> resumen(Levantamiento l, Usuario u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idLevantamiento", l.getIdLevantamiento());
        m.put("idSede", l.getIdSede());
        m.put("sede", sedeRepository.findById(l.getIdSede()).map(Sede::getNombreSede).orElse(""));
        m.put("anio", l.getAnio());
        m.put("periodo", l.getPeriodo());
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
        m.put("capturados", contar(regs, REVISADO));
        m.put("noEncontrados", contar(regs, NO_ENCONTRADO));
        m.put("nuevos", regs.stream().filter(r -> "NUEVO".equals(r.getOrigen())).count());
        m.put("transferidos", regs.stream().filter(r -> "TRANSFERIDO".equals(r.getOrigen())).count());
        m.put("resguardos", documentoRepository.findByIdLevantamiento(l.getIdLevantamiento()).stream()
                .filter(d -> "RESGUARDO".equals(d.getTipo())).count());
        m.put("fueraDeLugar", regs.stream().filter(r -> REVISADO.equals(r.getEstatus()) && Boolean.TRUE.equals(r.getFueraDeLugar())).count());
        m.put("fotosNuevas", regs.stream().filter(r -> Boolean.TRUE.equals(r.getFotoTomada())).count());
        m.put("soyCapturista", u.getIdUsuario().equals(l.getIdUsuarioCapturista()));
        m.put("puedoAdministrar", puedeAdministrar(u, l.getIdSede()));
        m.put("puedoBorrar", puedeAdministrar(u, l.getIdSede()) && ("EN_CURSO".equals(l.getEstado()) || permitirBorrarCerrados));
        m.put("almacenFotos", fotoStorage.destino());
        return m;
    }

    private List<Bien> bienesDeSede(Long idSede) {
        return bienRepository.findByActivoTrueOrderByIdBienDesc().stream()
                .filter(b -> b.getUbicacion() != null && b.getUbicacion().getSede() != null
                        && idSede.equals(b.getUbicacion().getSede().getIdSede()))
                .toList();
    }

    // "Inventario 2026-1" (semestre)
    private static String nombreInventario(Levantamiento l) {
        return "Inventario " + l.getAnio() + "-" + (l.getPeriodo() == null ? 1 : l.getPeriodo());
    }

    private static String textoPeriodo(Integer p) {
        return p != null && p == 2 ? "segundo semestre (julio a diciembre)" : "primer semestre (enero a junio)";
    }

    private static long contar(List<LevantamientoRegistro> regs, String estatus) {
        return regs.stream().filter(r -> estatus.equals(r.getEstatus())).count();
    }

    private static Long idPersona(Bien b) {
        return b.getUsuarioDirecto() != null ? b.getUsuarioDirecto().getIdPersona() : null;
    }

    private static Long idSedeDe(Bien b) {
        return b.getUbicacion() != null && b.getUbicacion().getSede() != null ? b.getUbicacion().getSede().getIdSede() : null;
    }

    // Capturar: levantamiento abierto y ser el capturista o administrador de la sede
    private void validarCaptura(Usuario u, Levantamiento l) {
        if (!"EN_CURSO".equals(l.getEstado())) throw new ResponseStatusException(HttpStatus.CONFLICT, "El levantamiento ya está cerrado");
        if (!u.getIdUsuario().equals(l.getIdUsuarioCapturista()) && !puedeAdministrar(u, l.getIdSede())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el capturista asignado puede registrar bienes");
        }
    }

    private Bien bienDeLaSede(Levantamiento l, Long idBien) {
        Bien bien = bienRepository.findByIdBienAndActivoTrue(idBien)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "El bien no existe o está dado de baja"));
        if (!l.getIdSede().equals(idSedeDe(bien))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este bien pertenece a otra sede (" +
                    (bien.getUbicacion() != null && bien.getUbicacion().getSede() != null ? bien.getUbicacion().getSede().getNombreSede() : "sin sede") + ")");
        }
        return bien;
    }

    private Ubicacion areaDeLaSede(Levantamiento l, Long idUbicacion) {
        if (idUbicacion == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige el área donde queda el bien");
        Ubicacion a = ubicacionRepository.findById(idUbicacion)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "El área no existe"));
        if (a.getSede() == null || !l.getIdSede().equals(a.getSede().getIdSede())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El área no es de esta sede");
        }
        return a;
    }

    private Persona personaActiva(Long idPersona) {
        return personaRepository.findByIdPersonaAndEstatusTrue(idPersona)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "La persona no existe o está inactiva"));
    }

    private static String vacioANull(String v) { return v == null || v.isBlank() ? null : v; }

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

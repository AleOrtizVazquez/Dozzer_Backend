package com.dozzer.service.impl;

import com.dozzer.entity.Persona;
import com.dozzer.entity.Rol;
import com.dozzer.entity.Usuario;
import com.dozzer.repository.PersonaRepository;
import com.dozzer.repository.RolRepository;
import com.dozzer.repository.UsuarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.stream.Collectors;

// CONTROL VEHICULAR. El sistema calcula todo (semáforo, salud, vencimientos, servicio, sospechas, puntos y podio);
// el responsable solo hace su Pit Stop semanal y el administrador de flota solo decide en tarjetas.
// Bloque independiente de bienes: usa sus propias tablas (vehiculos, vehiculo_*).
@Service
@Transactional
public class VehiculoService {

    private static final Logger log = LoggerFactory.getLogger(VehiculoService.class);
    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<Long> HITOS = Set.of(30L, 7L, 1L, 0L);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private FotoStorageService fotoStorage;
    @Autowired private PushService push;

    // ===================== Quién es =====================

    private Usuario usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        return usuarioRepository.findByCorreoIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }

    public boolean esAdminFlota() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "VEHICULOS_ADMINISTRAR".equals(a.getAuthority()));
    }

    private boolean esAdminGeneral() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private void soloAdmin() {
        if (!esAdminFlota()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el administrador de la flota");
    }

    private static Long idPersona(Usuario u) {
        return u.getPersona() != null ? u.getPersona().getIdPersona() : null;
    }

    private static String nombreDe(Usuario u) {
        return u.getPersona() != null && u.getPersona().getNombreCompleto() != null ? u.getPersona().getNombreCompleto() : u.getCorreo();
    }

    // Para la sesión larga: quien maneja un vehículo o administra la flota no debe estar entrando a cada rato
    @Transactional(readOnly = true)
    public boolean usaFlota(Usuario u) {
        try { return usaFlotaSinError(u); }
        catch (Exception e) { return false; }   // nunca debe impedir entrar
    }

    private boolean usaFlotaSinError(Usuario u) {
        if (u == null) return false;
        if (u.getRoles().stream().anyMatch(r -> "FLOTA".equalsIgnoreCase(r.getNombre()))) return true;
        Long p = idPersona(u);
        if (p == null) return false;
        Integer n = jdbc.queryForObject("select count(*) from vehiculos where activo and id_persona_responsable = ?", Integer.class, p);
        return n != null && n > 0;
    }

    // ===================== Perfil =====================

    @Transactional(readOnly = true)
    public Map<String, Object> perfil() {
        Usuario u = usuarioActual();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("admin", esAdminFlota());
        r.put("adminGeneral", esAdminGeneral());
        Long p = idPersona(u);
        List<Map<String, Object>> mios = p == null ? List.of()
                : jdbc.queryForList("select * from vehiculos where activo and id_persona_responsable = ? order by placas", p);
        LocalDate hoy = LocalDate.now(MX);
        List<Map<String, Object>> lista = new ArrayList<>();
        String sem = semana(LocalDateTime.now(MX));
        for (Map<String, Object> v : mios) {
            Map<String, Object> m = resumen(v, hoy, true);
            Integer n = jdbc.queryForObject("select count(*) from vehiculo_revisiones where id_vehiculo = ? and semana = ?", Integer.class, num(v.get("id_vehiculo")), sem);
            m.put("pit_semana", n != null && n > 0);
            lista.add(m);
        }
        r.put("vehiculos", lista);
        r.put("yo", p == null ? null : marcador(p, hoy));
        r.put("nombre", nombreDe(u));
        r.put("sin_persona", p == null);
        return r;
    }

    // Cuenta sin persona (ej. la cuenta principal del administrador): se liga una sola vez para ver "Mi vehículo"
    // Si la persona ya tiene otra cuenta (p. ej. la que se creó con "Dar acceso" para su vehículo), un administrador
    // puede quedarse con ella (reemplazar=true): la otra cuenta se desliga; si no es de administrador, además se desactiva.
    @Transactional
    public Map<String, Object> ligarMiPersona(Long idPersona, boolean reemplazar) {
        Usuario u = usuarioActual();
        if (u.getPersona() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu cuenta ya está ligada a " + u.getPersona().getNombreCompleto());
        if (idPersona == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige tu nombre");
        Persona p = personaRepository.findById(idPersona).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "La persona no existe"));
        List<Map<String, Object>> otras = jdbc.queryForList("select u.id_usuario, u.correo, " +
                "exists(select 1 from usuario_roles ur join roles r on r.id = ur.id_rol where ur.id_usuario = u.id_usuario and r.nombre = 'ADMIN') admin " +
                "from usuarios u where u.id_persona = ? and u.id_usuario <> ?", idPersona, u.getIdUsuario());
        if (!otras.isEmpty()) {
            String correos = otras.stream().map(o -> String.valueOf(o.get("correo"))).collect(Collectors.joining(", "));
            if (!reemplazar || !esAdminGeneral())
                throw new ResponseStatusException(HttpStatus.CONFLICT, p.getNombreCompleto() + " ya tiene otra cuenta en DOZZER (" + correos + ")");
            // Una cuenta de administrador solo se desliga del nombre (sigue funcionando); las demás se desactivan
            for (Map<String, Object> o : otras) {
                if (Boolean.TRUE.equals(o.get("admin")))
                    jdbc.update("update usuarios set id_persona = null where id_usuario = ?", num(o.get("id_usuario")));
                else
                    jdbc.update("update usuarios set id_persona = null, activo = false where id_usuario = ?", num(o.get("id_usuario")));
            }
        }
        u.setPersona(p);
        usuarioRepository.save(u);
        return perfil();
    }

    // ===================== Lista y ficha =====================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar() {
        soloAdmin();
        LocalDate hoy = LocalDate.now(MX);
        Map<Long, Map<String, Object>> ultimas = ultimasRevisiones();
        Map<Long, Integer> abiertos = conteoPendientes();
        Set<Long> conUsuario = personasConUsuario();
        List<Map<String, Object>> r = new ArrayList<>();
        for (Map<String, Object> v : jdbc.queryForList("select * from vehiculos where activo order by sede_nombre, placas")) {
            Long id = num(v.get("id_vehiculo"));
            Map<String, Object> m = resumen(v, hoy, false, ultimas.get(id), abiertos.getOrDefault(id, 0));
            m.put("codigo_qr", v.get("codigo_qr"));
            Long per = num(v.get("id_persona_responsable"));
            m.put("responsable_con_acceso", per != null && conUsuario.contains(per));
            r.add(m);
        }
        return r;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> ficha(Long id) {
        Map<String, Object> v = vehiculo(id);
        Usuario u = usuarioActual();
        boolean admin = esAdminFlota();
        if (!admin && !Objects.equals(num(v.get("id_persona_responsable")), idPersona(u))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este vehículo no está a tu cargo");
        }
        LocalDate hoy = LocalDate.now(MX);
        Map<String, Object> r = resumen(v, hoy, true, ultimasRevisiones(id).stream().findFirst().orElse(null), conteoPendientes().getOrDefault(id, 0));
        r.put("pendientes", jdbc.queryForList("select * from vehiculo_pendientes where id_vehiculo = ? and estado in ('ABIERTO','EN_TALLER') order by fecha_creacion desc", id));
        if (admin) {
            r.put("revisiones", ultimasRevisiones(id).stream().limit(12).map(this::revisionVista).toList());
            r.put("historial", jdbc.queryForList("select fecha, tipo, texto, quien from vehiculo_historial where id_vehiculo = ? order by fecha desc, id_evento desc limit 60", id));
            Long per = num(v.get("id_persona_responsable"));
            r.put("responsable_con_acceso", per != null && personasConUsuario().contains(per));
            r.put("responsable_correo", per == null ? null : personaRepository.findById(per).map(Persona::getCorreo).orElse(null));
        }
        return r;
    }

    private Map<String, Object> vehiculo(Long id) {
        List<Map<String, Object>> l = jdbc.queryForList("select * from vehiculos where id_vehiculo = ? and activo", id);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "El vehículo no existe o se dio de baja");
        return l.get(0);
    }

    private Map<String, Object> resumen(Map<String, Object> v, LocalDate hoy, boolean completo) {
        Long id = num(v.get("id_vehiculo"));
        return resumen(v, hoy, completo, ultimasRevisiones(id).stream().findFirst().orElse(null), conteoPendientes().getOrDefault(id, 0));
    }

    // Semáforo y salud calculados (nadie los captura)
    private Map<String, Object> resumen(Map<String, Object> v, LocalDate hoy, boolean completo, Map<String, Object> ultima, int abiertos) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("id_vehiculo", "consecutivo", "marca", "modelo", "anio", "color", "placas", "numero_serie", "tarjeta_circulacion",
                "numero_economico", "estado", "id_sede", "sede_nombre", "id_persona_responsable", "responsable_nombre", "poliza_numero", "aseguradora",
                "poliza_inicio", "poliza_vigencia", "tenencia_anio", "holograma", "verifica_sem1", "verifica_sem2", "proxima_verificacion",
                "km_actual", "km_fecha", "servicio_km", "servicio_fecha", "servicio_cada_km", "servicio_cada_meses", "notas")) m.put(k, v.get(k));
        if (completo) m.put("codigo_qr", v.get("codigo_qr"));
        List<Map<String, Object>> items = new ArrayList<>();
        int salud = 100;

        // Seguro
        LocalDate vig = fecha(v.get("poliza_vigencia"));
        if (vig == null) { items.add(item("poliza", "Seguro", "rojo", "Sin póliza registrada")); salud -= 30; }
        else {
            long d = ChronoUnit.DAYS.between(hoy, vig);
            if (d < 0) { items.add(item("poliza", "Seguro", "rojo", "Venció hace " + dias(-d))); salud -= 30; }
            else if (d <= 30) { items.add(item("poliza", "Seguro", "ambar", d == 0 ? "Vence hoy" : "Vence en " + dias(d))); salud -= 5; }
            else items.add(item("poliza", "Seguro", "verde", "Vigente hasta " + texto(vig)));
        }
        // Verificación
        LocalDate ver = fecha(v.get("proxima_verificacion"));
        if (ver == null) items.add(item("verificacion", "Verificación", "gris", "Sin fecha de próxima verificación"));
        else {
            long d = ChronoUnit.DAYS.between(hoy, ver);
            if (d < 0) { items.add(item("verificacion", "Verificación", "rojo", "Atrasada " + dias(-d))); salud -= 20; }
            else if (d <= 30) { items.add(item("verificacion", "Verificación", "ambar", "Toca en " + dias(d))); salud -= 5; }
            else items.add(item("verificacion", "Verificación", "verde", "Próxima: " + texto(ver)));
        }
        // Tenencia (se paga en el primer trimestre)
        Integer ten = entero(v.get("tenencia_anio"));
        if (ten == null) items.add(item("tenencia", "Tenencia", "gris", "Sin registro de pago"));
        else if (ten >= hoy.getYear()) items.add(item("tenencia", "Tenencia", "verde", "Pagada " + ten));
        else if (hoy.getMonthValue() > 3) { items.add(item("tenencia", "Tenencia", "rojo", "Falta pagar " + hoy.getYear())); salud -= 10; }
        else items.add(item("tenencia", "Tenencia", "ambar", "Pagar " + hoy.getYear() + " antes del 31 de marzo"));
        // Servicio (por kilometraje o por tiempo, lo que llegue primero)
        Map<String, Object> srv = servicio(v, hoy);
        String eSrv = String.valueOf(srv.get("estado"));
        items.add(item("servicio", "Servicio", eSrv, String.valueOf(srv.get("texto"))));
        if ("rojo".equals(eSrv)) salud -= 20; else if ("ambar".equals(eSrv)) salud -= 5;
        m.put("servicio", srv);
        // Reporte semanal
        if (ultima == null) items.add(item("reporte", "Pit Stop", "gris", "Todavía no hay reportes"));
        else {
            LocalDateTime f = fechaHora(ultima.get("fecha"));
            long d = f == null ? 999 : ChronoUnit.DAYS.between(f.toLocalDate(), hoy);
            String et = d == 0 ? "hoy" : d == 1 ? "ayer" : "hace " + dias(d);
            if (d > 14) { items.add(item("reporte", "Pit Stop", "ambar", "Último reporte " + et)); salud -= 10; }
            else items.add(item("reporte", "Pit Stop", "verde", "Último reporte " + et));
            m.put("ultima_revision", ultima.get("fecha"));
        }
        // Problemas abiertos
        if (abiertos > 0) { items.add(item("problemas", "Pendientes", "ambar", abiertos + (abiertos == 1 ? " problema por atender" : " problemas por atender"))); salud -= Math.min(30, 10 * abiertos); }

        String semaforo = items.stream().anyMatch(i -> "rojo".equals(i.get("estado"))) ? "rojo"
                : items.stream().anyMatch(i -> "ambar".equals(i.get("estado"))) ? "ambar" : "verde";
        m.put("items", items);
        m.put("semaforo", semaforo);
        m.put("salud", Math.max(0, Math.min(100, salud)));
        m.put("pendientes_abiertos", abiertos);
        return m;
    }

    private Map<String, Object> servicio(Map<String, Object> v, LocalDate hoy) {
        Map<String, Object> r = new LinkedHashMap<>();
        Integer km = entero(v.get("km_actual")), skm = entero(v.get("servicio_km"));
        int cadaKm = Optional.ofNullable(entero(v.get("servicio_cada_km"))).orElse(10000);
        int cadaMeses = Optional.ofNullable(entero(v.get("servicio_cada_meses"))).orElse(6);
        LocalDate sf = fecha(v.get("servicio_fecha"));
        Integer restanKm = km != null && skm != null ? skm + cadaKm - km : null;
        Long restanDias = sf != null ? ChronoUnit.DAYS.between(hoy, sf.plusMonths(cadaMeses)) : null;
        Integer porSemana = kmPorSemana(num(v.get("id_vehiculo")));
        r.put("restan_km", restanKm);
        r.put("restan_dias", restanDias);
        r.put("km_semana", porSemana);
        if (restanKm == null && restanDias == null) {
            r.put("estado", "gris");
            r.put("texto", "Sin registro del último servicio");
            return r;
        }
        // Predicción: con lo que recorre cada semana, cuándo le toca
        Long semanas = restanKm != null && porSemana != null && porSemana > 0 ? (long) Math.ceil(Math.max(0, restanKm) / (double) porSemana) : null;
        r.put("semanas", semanas);
        boolean vencido = (restanKm != null && restanKm <= 0) || (restanDias != null && restanDias < 0);
        boolean pronto = (restanKm != null && restanKm <= 1000) || (restanDias != null && restanDias <= 30) || (semanas != null && semanas <= 3);
        if (vencido) { r.put("estado", "rojo"); r.put("texto", restanKm != null && restanKm <= 0 ? "Pasado por " + fmt(-restanKm) + " km" : "Le tocaba hace " + dias(-restanDias)); }
        else if (pronto) {
            r.put("estado", "ambar");
            r.put("texto", semanas != null ? "Le toca en ~" + semanas + (semanas == 1 ? " semana" : " semanas") : restanKm != null ? "Le toca en " + fmt(restanKm) + " km" : "Le toca en " + dias(restanDias));
        } else {
            r.put("estado", "verde");
            r.put("texto", restanKm != null ? "Faltan " + fmt(restanKm) + " km" + (semanas != null ? " (~" + semanas + " semanas)" : "") : "Le toca en " + dias(restanDias));
        }
        return r;
    }

    private static Map<String, Object> item(String clave, String nombre, String estado, String texto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clave", clave); m.put("nombre", nombre); m.put("estado", estado); m.put("texto", texto);
        return m;
    }

    private Map<Long, Map<String, Object>> ultimasRevisiones() {
        Map<Long, Map<String, Object>> r = new HashMap<>();
        for (Map<String, Object> x : jdbc.queryForList("select distinct on (id_vehiculo) id_vehiculo, fecha, km from vehiculo_revisiones order by id_vehiculo, fecha desc")) {
            r.put(num(x.get("id_vehiculo")), x);
        }
        return r;
    }

    private List<Map<String, Object>> ultimasRevisiones(Long id) {
        return jdbc.queryForList("select * from vehiculo_revisiones where id_vehiculo = ? order by fecha desc limit 40", id);
    }

    private Map<Long, Integer> conteoPendientes() {
        Map<Long, Integer> r = new HashMap<>();
        jdbc.query("select id_vehiculo, count(*) n from vehiculo_pendientes where estado in ('ABIERTO','EN_TALLER') group by id_vehiculo",
                rs -> { r.put(rs.getLong(1), rs.getInt(2)); });
        return r;
    }

    private Set<Long> personasConUsuario() {
        return new HashSet<>(jdbc.queryForList("select id_persona from usuarios where id_persona is not null and activo", Long.class));
    }

    private Integer kmPorSemana(Long idVehiculo) {
        List<Map<String, Object>> l = jdbc.queryForList("select fecha, km from vehiculo_revisiones where id_vehiculo = ? order by fecha desc limit 8", idVehiculo);
        if (l.size() < 2) return null;
        LocalDateTime a = fechaHora(l.get(l.size() - 1).get("fecha")), b = fechaHora(l.get(0).get("fecha"));
        Integer ka = entero(l.get(l.size() - 1).get("km")), kb = entero(l.get(0).get("km"));
        if (a == null || b == null || ka == null || kb == null) return null;
        double semanas = Math.max(1, ChronoUnit.HOURS.between(a, b) / (24.0 * 7));
        return (int) Math.round(Math.max(0, kb - ka) / semanas);
    }

    private Map<String, Object> revisionVista(Map<String, Object> x) {
        Map<String, Object> m = new LinkedHashMap<>(x);
        m.remove("datos");
        try { m.put("detalle", x.get("datos") == null ? null : JSON.readValue(String.valueOf(x.get("datos")), Map.class)); } catch (Exception e) { m.put("detalle", null); }
        Object k = x.get("foto_key");
        m.put("foto_url", k == null ? null : fotoStorage.urlGrande(String.valueOf(k)));
        m.remove("foto_key");
        return m;
    }

    // ===================== Alta, edición y baja =====================

    public Map<String, Object> guardar(Long id, Map<String, Object> d) {
        soloAdmin();
        if (d == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Faltan datos");
        String placas = placa(txt(d.get("placas")));
        if (placas == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe las placas");
        Integer otro = jdbc.queryForObject("select count(*) from vehiculos where upper(placas) = ? and id_vehiculo <> ?", Integer.class, placas, id == null ? -1 : id);
        if (otro != null && otro > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un vehículo con las placas " + placas);
        Long idPersona = largo(d.get("id_persona_responsable"));
        String respNombre = txt(d.get("responsable_nombre"));
        if (idPersona != null) respNombre = personaRepository.findById(idPersona).map(Persona::getNombreCompleto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "La persona no existe"));
        Long idSede = largo(d.get("id_sede"));
        String sedeNombre = txt(d.get("sede_nombre"));
        if (idSede != null) sedeNombre = jdbc.queryForList("select nombre_sede from cat_sedes where id_sede = ?", String.class, idSede).stream().findFirst().orElse(sedeNombre);
        Object[] vals = {
                corto(txt(d.get("consecutivo")), 30), corto(txt(d.get("marca")), 60), corto(txt(d.get("modelo")), 80), entero(d.get("anio")),
                corto(txt(d.get("color")), 40), placas, corto(txt(d.get("numero_serie")), 40), corto(txt(d.get("tarjeta_circulacion")), 40),
                economico(txt(d.get("numero_economico"))), corto(txt(d.get("estado")), 80), idSede, corto(sedeNombre, 150), idPersona, corto(respNombre, 180),
                corto(txt(d.get("poliza_numero")), 60), corto(txt(d.get("aseguradora")), 80), sqlFecha(d.get("poliza_inicio")), sqlFecha(d.get("poliza_vigencia")),
                entero(d.get("tenencia_anio")), corto(txt(d.get("holograma")), 20), corto(txt(d.get("verifica_sem1")), 20), corto(txt(d.get("verifica_sem2")), 20),
                sqlFecha(d.get("proxima_verificacion")), Optional.ofNullable(entero(d.get("servicio_cada_km"))).orElse(10000),
                Optional.ofNullable(entero(d.get("servicio_cada_meses"))).orElse(6), corto(txt(d.get("notas")), 500)
        };
        String quien = nombreDe(usuarioActual());
        if (id == null) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(c -> {
                PreparedStatement ps = c.prepareStatement("insert into vehiculos (consecutivo, marca, modelo, anio, color, placas, numero_serie, tarjeta_circulacion, numero_economico, estado, id_sede, sede_nombre, " +
                        "id_persona_responsable, responsable_nombre, poliza_numero, aseguradora, poliza_inicio, poliza_vigencia, tenencia_anio, holograma, verifica_sem1, verifica_sem2, proxima_verificacion, " +
                        "servicio_cada_km, servicio_cada_meses, notas, codigo_qr, activo, fecha_creacion) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,?)", new String[]{"id_vehiculo"});
                for (int i = 0; i < vals.length; i++) ps.setObject(i + 1, vals[i]);
                ps.setString(vals.length + 1, UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
                ps.setTimestamp(vals.length + 2, ahora());
                return ps;
            }, kh);
            Long nuevo = kh.getKey().longValue();
            evento(nuevo, "ALTA", "Se registró el vehículo", quien);
            return ficha(nuevo);
        }
        Map<String, Object> antes = vehiculo(id);
        List<Object> args = new ArrayList<>(Arrays.asList(vals));
        args.add(ahora());
        args.add(id);
        jdbc.update("update vehiculos set consecutivo=?, marca=?, modelo=?, anio=?, color=?, placas=?, numero_serie=?, tarjeta_circulacion=?, numero_economico=?, estado=?, id_sede=?, sede_nombre=?, " +
                "id_persona_responsable=?, responsable_nombre=?, poliza_numero=?, aseguradora=?, poliza_inicio=?, poliza_vigencia=?, tenencia_anio=?, holograma=?, verifica_sem1=?, verifica_sem2=?, " +
                "proxima_verificacion=?, servicio_cada_km=?, servicio_cada_meses=?, notas=?, fecha_actualizacion=? where id_vehiculo=?", args.toArray());
        if (!Objects.equals(num(antes.get("id_persona_responsable")), idPersona) || !Objects.equals(antes.get("responsable_nombre"), respNombre)) {
            evento(id, "RESPONSABLE", "Nuevo responsable: " + (respNombre == null ? "nadie" : respNombre), quien);
        }
        evento(id, "EDICION", "Se actualizaron los datos generales", quien);
        return ficha(id);
    }

    public Map<String, Object> darDeBaja(Long id, String motivo) {
        soloAdmin();
        vehiculo(id);
        jdbc.update("update vehiculos set activo = false, fecha_actualizacion = ? where id_vehiculo = ?", ahora(), id);
        evento(id, "BAJA", "Se dio de baja el vehículo" + (motivo != null && !motivo.isBlank() ? ": " + corto(motivo.trim(), 300) : ""), nombreDe(usuarioActual()));
        return Map.of("success", true);
    }

    // Asignar responsable desde la ficha (lo más común), sin reescribir todo
    public Map<String, Object> asignarResponsable(Long id, Long idPersona) {
        soloAdmin();
        vehiculo(id);
        String nombre = null;
        if (idPersona != null) nombre = personaRepository.findById(idPersona).map(Persona::getNombreCompleto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "La persona no existe"));
        jdbc.update("update vehiculos set id_persona_responsable = ?, responsable_nombre = coalesce(?, responsable_nombre), fecha_actualizacion = ? where id_vehiculo = ?",
                idPersona, nombre, ahora(), id);
        evento(id, "RESPONSABLE", "Nuevo responsable: " + (nombre == null ? "nadie" : nombre), nombreDe(usuarioActual()));
        return ficha(id);
    }

    // ===================== Ligar responsables escritos como texto =====================

    // Vehículos con nombre de responsable pero sin persona del catálogo: propone a quién ligar cada uno
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sugerenciasResponsables() {
        soloAdmin();
        List<Persona> personas = personaRepository.findAll().stream().filter(p -> !Boolean.FALSE.equals(p.getEstatus()) && p.getNombreCompleto() != null).toList();
        List<Map<String, Object>> vehs = jdbc.queryForList("select id_vehiculo, placas, marca, modelo, responsable_nombre from vehiculos " +
                "where activo and id_persona_responsable is null and responsable_nombre is not null and trim(responsable_nombre) <> '' order by responsable_nombre, placas");
        List<Map<String, Object>> r = new ArrayList<>();
        for (Map<String, Object> v : vehs) {
            String nombre = String.valueOf(v.get("responsable_nombre"));
            List<Map<String, Object>> cands = new ArrayList<>();
            for (Persona p : personas) {
                double s = parecido(nombre, p.getNombreCompleto());
                if (s < 0.5) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id_persona", p.getIdPersona());
                c.put("nombre", p.getNombreCompleto());
                c.put("parecido", Math.round(s * 100));
                cands.add(c);
            }
            cands.sort((a, b) -> Long.compare((Long) b.get("parecido"), (Long) a.get("parecido")));
            if (cands.size() > 4) cands = new ArrayList<>(cands.subList(0, 4));
            Map<String, Object> m = new LinkedHashMap<>(v);
            m.put("candidatos", cands);
            // Se propone solo si el mejor es claro: muy parecido y sin otro casi igual de cerca
            boolean claro = !cands.isEmpty() && (Long) cands.get(0).get("parecido") >= 75
                    && (cands.size() == 1 || (Long) cands.get(0).get("parecido") - (Long) cands.get(1).get("parecido") >= 15);
            m.put("propuesta", claro ? cands.get(0).get("id_persona") : null);
            r.add(m);
        }
        return r;
    }

    // Liga varios de una vez (lo que el administrador confirmó). Cada vehículo se guarda por separado:
    // si uno falla, los demás quedan ligados y se informa cuál falló y por qué.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Map<String, Object> ligarResponsables(List<Map<String, Object>> pares) {
        soloAdmin();
        String quien = nombreDe(usuarioActual());
        int n = 0;
        List<String> fallas = new ArrayList<>();
        for (Map<String, Object> par : pares) {
            Long id = num(par.get("id_vehiculo")), per = num(par.get("id_persona"));
            if (id == null || per == null) continue;
            try {
                String nombre = personaRepository.findById(per).map(Persona::getNombreCompleto)
                        .orElseThrow(() -> new IllegalArgumentException("la persona ya no existe"));
                int filas = jdbc.update("update vehiculos set id_persona_responsable = ?, responsable_nombre = ?, fecha_actualizacion = ? where id_vehiculo = ? and activo",
                        per, nombre, ahora(), id);
                if (filas == 0) throw new IllegalArgumentException("el vehículo no existe o se dio de baja");
                evento(id, "RESPONSABLE", "Nuevo responsable: " + nombre, quien);
                n++;
            } catch (Exception e) {
                log.error("No se pudo ligar el vehículo {} a la persona {}", id, per, e);
                fallas.add("Vehículo " + id + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : corto(e.getMessage(), 200)));
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ligados", n);
        r.put("fallas", fallas);
        return r;
    }

    // Qué tanto se parecen dos nombres (0 a 1): palabras iguales sin importar orden ni acentos, tolerando una letra de diferencia
    private static double parecido(String a, String b) {
        List<String> x = Arrays.stream(norm(a).split(" ")).filter(s -> s.length() > 1).toList();
        List<String> y = new ArrayList<>(Arrays.stream(norm(b).split(" ")).filter(s -> s.length() > 1).toList());
        if (x.isEmpty() || y.isEmpty()) return 0;
        int total = Math.max(x.size(), y.size());
        double iguales = 0;
        for (String w : x) {
            String mejor = null; double pts = 0;
            for (String z : y) {
                double p = w.equals(z) ? 1 : (w.length() >= 4 && z.length() >= 4 && distancia(w, z) <= 1) ? 0.85 : 0;
                if (p > pts) { pts = p; mejor = z; }
            }
            if (mejor != null) { iguales += pts; y.remove(mejor); }
        }
        return iguales / total;
    }

    private static int distancia(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    // ===================== Importar el Excel (una vez) =====================

    public Map<String, Object> importar(List<Map<String, Object>> filas) {
        soloAdmin();
        if (filas == null || filas.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo no trae vehículos");
        if (filas.size() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Demasiadas filas");
        String quien = nombreDe(usuarioActual());
        // Personas y sedes para ligar por nombre (sin importar el orden de nombre y apellidos)
        Map<String, List<Persona>> porNombre = new HashMap<>();
        for (Persona p : personaRepository.findAll()) {
            if (Boolean.FALSE.equals(p.getEstatus())) continue;
            porNombre.computeIfAbsent(firma(p.getNombreCompleto()), k -> new ArrayList<>()).add(p);
        }
        List<Map<String, Object>> sedes = jdbc.queryForList("select id_sede, nombre_sede from cat_sedes");
        int creados = 0, actualizados = 0;
        List<String> sinResponsable = new ArrayList<>(), sinSede = new ArrayList<>(), errores = new ArrayList<>();
        int anio = LocalDate.now(MX).getYear();
        for (Map<String, Object> f : filas) {
            String placas = placa(txt(f.get("placas")));
            if (placas == null) { errores.add("Fila sin placas: " + Optional.ofNullable(txt(f.get("modelo"))).orElse("?")); continue; }
            try {
                Map<String, Object> d = new HashMap<>(f);
                d.put("placas", placas);
                String resp = txt(f.get("responsable"));
                List<Persona> cand = resp == null ? List.of() : porNombre.getOrDefault(firma(resp), List.of());
                if (cand.size() == 1) d.put("id_persona_responsable", cand.get(0).getIdPersona());
                else if (resp != null) sinResponsable.add(placas + " · " + resp);
                d.put("responsable_nombre", resp);
                String sede = txt(f.get("sede"));
                Long idSede = null;
                if (sede != null) {
                    String ns = norm(sede);
                    for (Map<String, Object> s : sedes) if (norm(String.valueOf(s.get("nombre_sede"))).equals(ns)) idSede = num(s.get("id_sede"));
                    if (idSede == null) for (Map<String, Object> s : sedes) {
                        String n = norm(String.valueOf(s.get("nombre_sede")));
                        if (!n.isEmpty() && (n.contains(ns) || ns.contains(n))) { idSede = num(s.get("id_sede")); break; }
                    }
                    if (idSede == null) sinSede.add(placas + " · " + sede);
                }
                d.put("id_sede", idSede);
                d.put("sede_nombre", sede);
                String ten = txt(f.get("tenencia"));
                if (ten != null && norm(ten).startsWith("PAGAD")) d.put("tenencia_anio", anio);
                else if (ten != null && ten.matches("\\d{4}")) d.put("tenencia_anio", Integer.parseInt(ten));
                List<Long> existe = jdbc.queryForList("select id_vehiculo from vehiculos where upper(placas) = ?", Long.class, placas);
                if (existe.isEmpty()) {
                    Map<String, Object> creado = guardarSinPermiso(null, d, quien);
                    if (creado != null) creados++;
                } else {
                    jdbc.update("update vehiculos set activo = true where id_vehiculo = ?", existe.get(0));
                    guardarSinPermiso(existe.get(0), d, quien);
                    actualizados++;
                }
            } catch (ResponseStatusException e) {
                errores.add(placas + ": " + e.getReason());
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("creados", creados);
        r.put("actualizados", actualizados);
        r.put("sinResponsable", sinResponsable);
        r.put("sinSede", sinSede);
        r.put("errores", errores);
        return r;
    }

    private Map<String, Object> guardarSinPermiso(Long id, Map<String, Object> d, String quien) {
        // Mismas reglas que guardar(): la importación solo la hace el administrador (ya validado arriba)
        return guardar(id, d);
    }

    // ===================== Decisiones (tarjetas para el administrador de flota) =====================

    @Transactional(readOnly = true)
    public Map<String, Object> decisiones() {
        soloAdmin();
        LocalDate hoy = LocalDate.now(MX);
        Set<String> pospuestos = new HashSet<>();
        jdbc.query("select id_vehiculo, clave from vehiculo_pospuestos where hasta >= ?", rs -> { pospuestos.add(rs.getLong(1) + "|" + rs.getString(2)); }, java.sql.Date.valueOf(hoy));
        List<Map<String, Object>> vs = jdbc.queryForList("select * from vehiculos where activo order by placas");
        Map<Long, Map<String, Object>> porId = new HashMap<>();
        for (Map<String, Object> v : vs) porId.put(num(v.get("id_vehiculo")), v);
        Map<Long, Map<String, Object>> ultimas = ultimasRevisiones();
        Set<Long> conUsuario = personasConUsuario();
        List<Map<String, Object>> tarjetas = new ArrayList<>();
        int enOrden = 0;
        List<Long> sinAcceso = new ArrayList<>(), sinResponsable = new ArrayList<>();

        for (Map<String, Object> v : vs) {
            Long id = num(v.get("id_vehiculo"));
            int antes = tarjetas.size();
            LocalDate vig = fecha(v.get("poliza_vigencia"));
            if (!pospuestos.contains(id + "|poliza")) {
                if (vig == null) tarjetas.add(tarjeta("poliza", "rojo", v, "No tiene póliza de seguro registrada", "Registra la póliza para saber cuándo vence.", null,
                        accion("poliza", "Registrar póliza", true), accion("posponer", "Después", false)));
                else {
                    long d = ChronoUnit.DAYS.between(hoy, vig);
                    if (d < 0) tarjetas.add(tarjeta("poliza", "rojo", v, "Seguro vencido hace " + dias(-d), corto(Objects.toString(v.get("aseguradora"), ""), 80), null,
                            accion("poliza", "Ya lo renové", true), accion("posponer", "Recordar después", false)));
                    else if (d <= 15) tarjetas.add(tarjeta("poliza", "ambar", v, d == 0 ? "El seguro vence hoy" : "El seguro vence en " + dias(d), corto(Objects.toString(v.get("aseguradora"), ""), 80), null,
                            accion("poliza", "Ya lo renové", true), accion("posponer", "Recordar después", false)));
                }
            }
            LocalDate ver = fecha(v.get("proxima_verificacion"));
            if (ver != null && !pospuestos.contains(id + "|verificacion")) {
                long d = ChronoUnit.DAYS.between(hoy, ver);
                if (d < 0) tarjetas.add(tarjeta("verificacion", "rojo", v, "Verificación atrasada " + dias(-d), "Holograma " + Objects.toString(v.get("holograma"), "—"), null,
                        accion("verificacion", "Ya se verificó", true), accion("posponer", "Recordar después", false)));
                else if (d <= 15) tarjetas.add(tarjeta("verificacion", "ambar", v, "Le toca verificar en " + dias(d), "Meses: " + Objects.toString(v.get("verifica_sem1"), "") + " / " + Objects.toString(v.get("verifica_sem2"), ""), null,
                        accion("verificacion", "Ya se verificó", true), accion("posponer", "Recordar después", false)));
            }
            Integer ten = entero(v.get("tenencia_anio"));
            if (ten != null && ten < hoy.getYear() && !pospuestos.contains(id + "|tenencia")) {
                tarjetas.add(tarjeta("tenencia", hoy.getMonthValue() > 3 ? "rojo" : "ambar", v, "Tenencia " + hoy.getYear() + " sin pagar", "Se paga en el primer trimestre del año.", null,
                        accion("tenencia", "Ya se pagó", true), accion("posponer", "Recordar después", false)));
            }
            Map<String, Object> srv = servicio(v, hoy);
            boolean enTaller = jdbc.queryForObject("select count(*) from vehiculo_pendientes where id_vehiculo = ? and estado = 'EN_TALLER'", Integer.class, id) > 0;
            if (!enTaller && ("rojo".equals(srv.get("estado")) || ("ambar".equals(srv.get("estado")) && srv.get("semanas") != null && ((Number) srv.get("semanas")).longValue() <= 2))
                    && !pospuestos.contains(id + "|servicio")) {
                tarjetas.add(tarjeta("servicio", String.valueOf(srv.get("estado")), v, "rojo".equals(srv.get("estado")) ? "Servicio vencido" : "Pronto le toca servicio",
                        String.valueOf(srv.get("texto")), null, accion("taller", "Mandar al taller", true), accion("servicio_hecho", "Ya se hizo", false)));
            }
            // Mismo problema varias veces: posible falla de fondo
            if (!pospuestos.contains(id + "|fuga")) {
                int bajos = 0;
                for (String dat : jdbc.queryForList("select datos from vehiculo_revisiones where id_vehiculo = ? and fecha >= ?", String.class, id, Timestamp.valueOf(LocalDateTime.now(MX).minusDays(60)))) {
                    if (leer(dat).get("niveles") instanceof Map<?, ?> nv) { Integer a = entero(nv.get("aceite")); if (a != null && a < 35) bajos++; }
                }
                Integer abierto = jdbc.queryForObject("select count(*) from vehiculo_pendientes where id_vehiculo = ? and estado in ('ABIERTO','EN_TALLER') and (titulo like 'Posible fuga%' or titulo like 'Aceite%')", Integer.class, id);
                if (bajos >= 3 && (abierto == null || abierto == 0)) {
                    tarjetas.add(tarjeta("fuga", "rojo", v, "Aceite bajo " + bajos + " veces en 2 meses", "Puede haber una fuga. Conviene revisarlo.", null,
                            accion("taller", "Mandar al taller", true), accion("posponer", "No es nada", false)));
                }
            }
            // Responsable sin reportar 2 semanas o más
            Map<String, Object> u = ultimas.get(id);
            Long per = num(v.get("id_persona_responsable"));
            if (per != null && conUsuario.contains(per) && !pospuestos.contains(id + "|reporte")) {
                LocalDateTime f = u == null ? null : fechaHora(u.get("fecha"));
                long d = f == null ? 999 : ChronoUnit.DAYS.between(f.toLocalDate(), hoy);
                // Solo si ya reportaba y dejó de hacerlo (a quien nunca ha reportado le llega el recordatorio de los lunes)
                if (f != null && d >= 14) tarjetas.add(tarjeta("reporte", "ambar", v, "Sin Pit Stop desde hace " + dias(d),
                        Objects.toString(v.get("responsable_nombre"), ""), null, accion("recordar", "Recordarle", true), accion("posponer", "Después", false)));
            }
            if (per == null) sinResponsable.add(id);
            else if (!conUsuario.contains(per)) sinAcceso.add(id);
            if (tarjetas.size() == antes) enOrden++;
        }
        // Problemas reportados y vehículos en taller
        for (Map<String, Object> p : jdbc.queryForList("select * from vehiculo_pendientes where estado in ('ABIERTO','EN_TALLER') order by fecha_creacion")) {
            Map<String, Object> v = porId.get(num(p.get("id_vehiculo")));
            if (v == null) continue;
            if ("EN_TALLER".equals(p.get("estado"))) {
                tarjetas.add(tarjeta("en_taller", "azul", v, "En taller: " + p.get("titulo"), "Desde " + texto(fechaHora(p.get("fecha_actualizacion") != null ? p.get("fecha_actualizacion") : p.get("fecha_creacion")).toLocalDate()), p,
                        accion("listo", "Ya quedó", true)));
            } else {
                tarjetas.add(tarjeta("problema", "PROBLEMA_GRAVE".equals(p.get("tipo")) ? "rojo" : "ambar", v, String.valueOf(p.get("titulo")),
                        "Reportó " + Objects.toString(v.get("responsable_nombre"), "el responsable") + " · " + texto(fechaHora(p.get("fecha_creacion")).toLocalDate()), p,
                        accion("taller", "Mandar al taller", true), accion("descartar", "No es nada", false)));
            }
        }
        // Reportes sospechosos
        for (Map<String, Object> x : jdbc.queryForList("select r.id_revision, r.id_vehiculo, r.sospecha, r.fecha, r.quien, r.km, r.foto_key from vehiculo_revisiones r where r.calificacion = 'REVISAR' order by r.fecha")) {
            Map<String, Object> v = porId.get(num(x.get("id_vehiculo")));
            if (v == null) continue;
            Map<String, Object> t = tarjeta("sospecha", "ambar", v, "Pit Stop raro: " + x.get("sospecha"), Objects.toString(x.get("quien"), "") + " · " + fmt(entero(x.get("km"))) + " km", null,
                    accion("rev_ok", "Está bien", true), accion("rev_falsa", "Es falso", false));
            t.put("idRevision", x.get("id_revision"));
            tarjetas.add(t);
        }
        if (vs.isEmpty()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tipo", "importar"); t.put("gravedad", "azul"); t.put("titulo", "Carga tu parque vehicular");
            t.put("detalle", "Sube el Excel de vehículos una sola vez. DOZZER revisa los errores antes de guardar.");
            t.put("acciones", List.of(accion("importar", "Subir Excel", true), accion("nuevo", "Agregar uno", false)));
            tarjetas.add(0, t);
        }
        if (!sinAcceso.isEmpty()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tipo", "accesos"); t.put("gravedad", "azul");
            t.put("titulo", sinAcceso.size() + (sinAcceso.size() == 1 ? " responsable no puede entrar a DOZZER" : " responsables no pueden entrar a DOZZER"));
            t.put("detalle", "Dales acceso para que hagan su Pit Stop desde el celular.");
            t.put("ids", sinAcceso);
            t.put("acciones", List.of(accion("ver_accesos", "Dar accesos", true)));
            tarjetas.add(t);
        }
        if (!sinResponsable.isEmpty()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tipo", "sin_responsable"); t.put("gravedad", "azul");
            t.put("titulo", sinResponsable.size() + (sinResponsable.size() == 1 ? " vehículo sin responsable ligado" : " vehículos sin responsable ligado"));
            t.put("detalle", "Elige quién lo trae para que pueda reportarlo.");
            t.put("ids", sinResponsable);
            t.put("acciones", List.of(accion("ver_sin_responsable", "Elegir responsables", true)));
            tarjetas.add(t);
        }
        // Lo más urgente primero
        Map<String, Integer> peso = Map.of("rojo", 0, "ambar", 1, "azul", 2);
        tarjetas.sort(Comparator.comparingInt(t -> peso.getOrDefault(String.valueOf(t.get("gravedad")), 3)));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", vs.size());
        r.put("enOrden", enOrden);
        r.put("tarjetas", tarjetas);
        return r;
    }

    @SafeVarargs
    private Map<String, Object> tarjeta(String tipo, String gravedad, Map<String, Object> v, String titulo, String detalle, Map<String, Object> pendiente, Map<String, Object>... acciones) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("tipo", tipo);
        t.put("gravedad", gravedad);
        t.put("idVehiculo", v.get("id_vehiculo"));
        t.put("vehiculo", (Objects.toString(v.get("marca"), "") + " " + Objects.toString(v.get("modelo"), "")).trim());
        t.put("placas", v.get("placas"));
        t.put("responsable", v.get("responsable_nombre"));
        t.put("titulo", titulo);
        t.put("detalle", detalle);
        if (pendiente != null) t.put("idPendiente", pendiente.get("id_pendiente"));
        t.put("acciones", Arrays.asList(acciones));
        return t;
    }

    private static Map<String, Object> accion(String id, String texto, boolean primaria) {
        return Map.of("id", id, "texto", texto, "primaria", primaria);
    }

    // ===================== Acciones del administrador =====================

    public Map<String, Object> renovarPoliza(Long id, Map<String, Object> d) {
        soloAdmin();
        Map<String, Object> v = vehiculo(id);
        java.sql.Date vig = sqlFecha(d.get("vigencia"));
        if (vig == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige hasta cuándo es válido el seguro");
        if (!vig.toLocalDate().isAfter(LocalDate.now(MX))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La nueva vigencia debe ser después de hoy");
        String numero = Optional.ofNullable(corto(txt(d.get("numero")), 60)).orElse(Objects.toString(v.get("poliza_numero"), null));
        String aseg = Optional.ofNullable(corto(txt(d.get("aseguradora")), 80)).orElse(Objects.toString(v.get("aseguradora"), null));
        jdbc.update("update vehiculos set poliza_inicio = ?, poliza_vigencia = ?, poliza_numero = ?, aseguradora = ?, fecha_actualizacion = ? where id_vehiculo = ?",
                java.sql.Date.valueOf(LocalDate.now(MX)), vig, numero, aseg, ahora(), id);
        quitarPospuesto(id, "poliza");
        evento(id, "SEGURO", "Seguro renovado hasta " + texto(vig.toLocalDate()) + (aseg != null ? " (" + aseg + ")" : ""), nombreDe(usuarioActual()));
        return Map.of("success", true);
    }

    public Map<String, Object> verificacionHecha(Long id, Map<String, Object> d) {
        soloAdmin();
        vehiculo(id);
        java.sql.Date prox = sqlFecha(d.get("proxima"));
        if (prox == null || !prox.toLocalDate().isAfter(LocalDate.now(MX))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige la fecha de la próxima verificación");
        String holo = corto(txt(d.get("holograma")), 20);
        jdbc.update("update vehiculos set proxima_verificacion = ?, holograma = coalesce(?, holograma), fecha_actualizacion = ? where id_vehiculo = ?", prox, holo, ahora(), id);
        quitarPospuesto(id, "verificacion");
        evento(id, "VERIFICACION", "Verificado. Próxima: " + texto(prox.toLocalDate()) + (holo != null ? " · holograma " + holo : ""), nombreDe(usuarioActual()));
        return Map.of("success", true);
    }

    public Map<String, Object> tenenciaPagada(Long id, Map<String, Object> d) {
        soloAdmin();
        vehiculo(id);
        int anio = Optional.ofNullable(entero(d == null ? null : d.get("anio"))).orElse(LocalDate.now(MX).getYear());
        jdbc.update("update vehiculos set tenencia_anio = ?, fecha_actualizacion = ? where id_vehiculo = ?", anio, ahora(), id);
        quitarPospuesto(id, "tenencia");
        evento(id, "TENENCIA", "Tenencia " + anio + " pagada", nombreDe(usuarioActual()));
        return Map.of("success", true);
    }

    // Servicio hecho: se registra el km y la fecha para calcular el siguiente
    public Map<String, Object> servicioHecho(Long id, Map<String, Object> d) {
        soloAdmin();
        Map<String, Object> v = vehiculo(id);
        Integer km = entero(d == null ? null : d.get("km"));
        if (km == null) km = entero(v.get("km_actual"));
        String trabajos = trabajos(d == null ? null : d.get("trabajos"));
        jdbc.update("update vehiculos set servicio_km = ?, servicio_fecha = ?, km_actual = greatest(coalesce(km_actual, 0), coalesce(?, 0)), fecha_actualizacion = ? where id_vehiculo = ?",
                km, java.sql.Date.valueOf(LocalDate.now(MX)), km, ahora(), id);
        quitarPospuesto(id, "servicio");
        evento(id, "SERVICIO", "Servicio hecho" + (km != null ? " a los " + fmt(km) + " km" : "") + (trabajos != null ? ": " + trabajos : ""), nombreDe(usuarioActual()));
        return Map.of("success", true);
    }

    // Mandar al taller: un problema reportado, un servicio o una alerta (fuga)
    public Map<String, Object> mandarTaller(Long id, Map<String, Object> d) {
        soloAdmin();
        Map<String, Object> v = vehiculo(id);
        Long idPend = largo(d == null ? null : d.get("idPendiente"));
        String quien = nombreDe(usuarioActual());
        if (idPend != null) {
            int n = jdbc.update("update vehiculo_pendientes set estado = 'EN_TALLER', fecha_actualizacion = ?, atendio = ? where id_pendiente = ? and id_vehiculo = ? and estado = 'ABIERTO'",
                    ahora(), quien, idPend, id);
            if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese pendiente ya se atendió");
        } else {
            String titulo = Optional.ofNullable(corto(txt(d == null ? null : d.get("titulo")), 200)).orElse("Servicio");
            jdbc.update("insert into vehiculo_pendientes (id_vehiculo, tipo, titulo, estado, km, fecha_creacion, fecha_actualizacion, atendio) values (?,?,?,?,?,?,?,?)",
                    id, "SERVICIO", titulo, "EN_TALLER", entero(v.get("km_actual")), ahora(), ahora(), quien);
            String clave = txt(d == null ? null : d.get("clave"));
            if (clave != null) quitarPospuesto(id, clave);
        }
        evento(id, "TALLER", "Se mandó al taller", quien);
        return Map.of("success", true);
    }

    // Ya quedó / No es nada. Si el taller arregló algo que el responsable reportaba "bien", su confianza baja sola.
    public Map<String, Object> cerrarPendiente(Long idPendiente, Map<String, Object> d) {
        soloAdmin();
        List<Map<String, Object>> l = jdbc.queryForList("select * from vehiculo_pendientes where id_pendiente = ?", idPendiente);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe");
        Map<String, Object> p = l.get(0);
        Long id = num(p.get("id_vehiculo"));
        String estado = "DESCARTADO".equals(txt(d == null ? null : d.get("estado"))) ? "DESCARTADO" : "RESUELTO";
        String trabajos = trabajos(d == null ? null : d.get("trabajos"));
        String quien = nombreDe(usuarioActual());
        jdbc.update("update vehiculo_pendientes set estado = ?, trabajos = ?, fecha_actualizacion = ?, atendio = ? where id_pendiente = ?", estado, trabajos, ahora(), quien, idPendiente);
        if ("RESUELTO".equals(estado)) {
            evento(id, "TALLER", "Quedó: " + p.get("titulo") + (trabajos != null ? " (" + trabajos + ")" : ""), quien);
            if (trabajos != null && (trabajos.contains("Servicio") || trabajos.contains("Aceite"))) {
                jdbc.update("update vehiculos set servicio_km = km_actual, servicio_fecha = ? where id_vehiculo = ?", java.sql.Date.valueOf(LocalDate.now(MX)), id);
            }
            if (trabajos != null && !String.valueOf(p.get("tipo")).startsWith("PROBLEMA")) revisarContradiccion(id, trabajos, fechaHora(p.get("fecha_creacion")));
        } else evento(id, "TALLER", "Descartado: " + p.get("titulo"), quien);
        return Map.of("success", true);
    }

    private void revisarContradiccion(Long id, String trabajos, LocalDateTime desde) {
        List<Map<String, Object>> l = jdbc.queryForList("select id_revision, datos from vehiculo_revisiones where id_vehiculo = ? and fecha <= ? order by fecha desc limit 4",
                id, Timestamp.valueOf(desde == null ? LocalDateTime.now(MX) : desde));
        if (l.size() < 2) return;
        boolean todoBienFrenos = true, todoBienLlantas = true;
        for (Map<String, Object> x : l) {
            Map<?, ?> dat = leer(x.get("datos"));
            Map<?, ?> niv = dat.get("niveles") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> ll = dat.get("llantas") instanceof Map<?, ?> m2 ? m2 : Map.of();
            Integer fr = entero(niv.get("frenos"));
            if (fr == null || fr < 70) todoBienFrenos = false;
            if (ll.values().stream().anyMatch(s -> !"ok".equals(String.valueOf(s)))) todoBienLlantas = false;
        }
        boolean contradice = (trabajos.contains("Frenos") && todoBienFrenos) || (trabajos.contains("Llantas") && todoBienLlantas);
        if (contradice) {
            jdbc.update("update vehiculo_revisiones set contradicha = true where id_revision = ?", num(l.get(0).get("id_revision")));
            evento(id, "CONFIANZA", "El taller encontró algo que los Pit Stop reportaban bien", "DOZZER");
        }
    }

    public Map<String, Object> posponer(Long id, Map<String, Object> d) {
        soloAdmin();
        vehiculo(id);
        String clave = txt(d == null ? null : d.get("clave"));
        if (clave == null || !Set.of("poliza", "verificacion", "tenencia", "servicio", "fuga", "reporte").contains(clave)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aviso no válido");
        int diasN = Math.max(1, Math.min(60, Optional.ofNullable(entero(d.get("dias"))).orElse(7)));
        jdbc.update("delete from vehiculo_pospuestos where id_vehiculo = ? and clave = ?", id, clave);
        jdbc.update("insert into vehiculo_pospuestos (id_vehiculo, clave, hasta) values (?,?,?)", id, clave, java.sql.Date.valueOf(LocalDate.now(MX).plusDays(diasN)));
        return Map.of("success", true);
    }

    private void quitarPospuesto(Long id, String clave) {
        jdbc.update("delete from vehiculo_pospuestos where id_vehiculo = ? and clave = ?", id, clave);
    }

    public Map<String, Object> calificarRevision(Long idRevision, String calificacion) {
        soloAdmin();
        if (!Set.of("OK", "FALSA", "BIEN").contains(calificacion)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Calificación no válida");
        List<Map<String, Object>> l = jdbc.queryForList("select id_vehiculo from vehiculo_revisiones where id_revision = ?", idRevision);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe");
        // BIEN: +5 de reconocimiento. FALSA: un reporte falso cuesta más que cualquier problema reportado.
        jdbc.update("update vehiculo_revisiones set puntos = case " +
                        "when ? = 'FALSA' then -30 " +
                        "when ? = 'BIEN' then (case when calificacion = 'FALSA' then 15 when calificacion = 'BIEN' then puntos else puntos + 5 end) " +
                        "else (case when calificacion = 'FALSA' then 10 when calificacion = 'BIEN' then puntos - 5 else puntos end) end, " +
                        "calificacion = ? where id_revision = ?", calificacion, calificacion, calificacion, idRevision);
        if ("FALSA".equals(calificacion)) evento(num(l.get(0).get("id_vehiculo")), "CONFIANZA", "Un Pit Stop se marcó como falso", nombreDe(usuarioActual()));
        return Map.of("success", true);
    }

    public Map<String, Object> recordar(Long id) {
        soloAdmin();
        Map<String, Object> v = vehiculo(id);
        Long per = num(v.get("id_persona_responsable"));
        Set<Long> ids = per == null ? Set.of() : new HashSet<>(jdbc.queryForList("select id_usuario from usuarios where id_persona = ? and activo", Long.class, per));
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "El responsable todavía no tiene acceso a DOZZER");
        push.enviarDespues(ids, "🏁 Tu Pit Stop semanal", "Tu " + Objects.toString(v.get("modelo"), "vehículo") + " " + v.get("placas") + " espera su revisión. Son 30 segundos.", "/?ir=pitstop", "pitstop-" + id);
        quitarPospuesto(id, "reporte");
        jdbc.update("insert into vehiculo_pospuestos (id_vehiculo, clave, hasta) values (?,?,?) on conflict do nothing", id, "reporte", java.sql.Date.valueOf(LocalDate.now(MX).plusDays(3)));
        return Map.of("success", true, "avisados", ids.size());
    }

    // Acceso del responsable: crea su cuenta (o le da una contraseña nueva) y prepara el mensaje para WhatsApp
    public Map<String, Object> darAcceso(Long id, Map<String, Object> d) {
        soloAdmin();
        Map<String, Object> v = vehiculo(id);
        Long per = num(v.get("id_persona_responsable"));
        if (per == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Primero elige al responsable del vehículo");
        Persona p = personaRepository.findById(per).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "La persona no existe"));
        String temporal = temporal();
        Optional<Usuario> existente = usuarioRepository.findAll().stream().filter(u -> u.getPersona() != null && per.equals(u.getPersona().getIdPersona())).findFirst();
        Usuario u;
        if (existente.isPresent()) {
            u = existente.get();
            if (u.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getNombre()))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Es administrador: que use su contraseña");
            u.setActivo(true);
        } else {
            String correo = Optional.ofNullable(txt(d == null ? null : d.get("correo"))).orElse(p.getCorreo());
            if (correo == null || !correo.contains("@")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe el correo del responsable");
            correo = correo.trim().toLowerCase();
            if (usuarioRepository.findByCorreoIgnoreCase(correo).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese correo ya lo usa otra cuenta");
            u = new Usuario();
            u.setCorreo(correo);
            u.setPersona(p);
            u.setActivo(true);
            if (u.getFechaCreacion() == null) u.setFechaCreacion(LocalDateTime.now(MX));
            Rol empleado = rolRepository.findByNombre("EMPLEADO").orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No existe el rol EMPLEADO"));
            u.setRoles(new HashSet<>(Set.of(empleado)));
            if (p.getSede() != null) u.getSedes().add(p.getSede());
        }
        u.setPassword(passwordEncoder.encode(temporal));
        u.setDebeCambiarPassword(true);
        usuarioRepository.save(u);
        evento(id, "ACCESO", "Se dio acceso a DOZZER a " + p.getNombreCompleto(), nombreDe(usuarioActual()));
        String mensaje = "Hola " + primerNombre(p.getNombreCompleto()) + ", ya tienes acceso a DOZZER para el control de tu vehículo " + v.get("placas") + ".\n\n"
                + "Entra a https://dozzer.pages.dev\nCorreo: " + u.getCorreo() + "\nContraseña temporal: " + temporal + "\n\n"
                + "Al entrar te pedirá una contraseña nueva. Cada lunes haz tu Pit Stop: son 30 segundos.";
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("correo", u.getCorreo());
        r.put("password", temporal);
        r.put("mensaje", mensaje);
        return r;
    }

    // Administradores de flota (solo el administrador general los elige)
    @Transactional(readOnly = true)
    public List<Map<String, Object>> administradoresFlota() {
        if (!esAdminGeneral()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el administrador");
        return jdbc.queryForList("select u.id_usuario, u.correo, p.nombre_completo nombre, " +
                "exists(select 1 from usuario_roles ur join roles r on r.id = ur.id_rol where ur.id_usuario = u.id_usuario and r.nombre = 'FLOTA') flota, " +
                "exists(select 1 from usuario_roles ur join roles r on r.id = ur.id_rol where ur.id_usuario = u.id_usuario and r.nombre = 'ADMIN') admin " +
                "from usuarios u left join cat_personas p on p.id_persona = u.id_persona where u.activo order by coalesce(p.nombre_completo, u.correo)");
    }

    public Map<String, Object> cambiarAdministradorFlota(Long idUsuario, boolean activo) {
        if (!esAdminGeneral()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el administrador");
        Long rol = jdbc.queryForObject("select id from roles where nombre = 'FLOTA'", Long.class);
        jdbc.update("delete from usuario_roles where id_usuario = ? and id_rol = ?", idUsuario, rol);
        if (activo) jdbc.update("insert into usuario_roles (id_usuario, id_rol) values (?, ?)", idUsuario, rol);
        return Map.of("success", true);
    }

    // ===================== Pit Stop del responsable =====================

    // El vehículo se puede revisar si es suyo o si escaneó el QR que trae pegado (cambio de unidad)
    private Map<String, Object> vehiculoParaRevisar(Long id, String qr) {
        Map<String, Object> v = vehiculo(id);
        Usuario u = usuarioActual();
        boolean suyo = Objects.equals(num(v.get("id_persona_responsable")), idPersona(u));
        boolean porQr = qr != null && qr.trim().equalsIgnoreCase(String.valueOf(v.get("codigo_qr")));
        if (!suyo && !porQr && !esAdminFlota()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Escanea el QR del vehículo que traes");
        return v;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> porQr(String codigo) {
        String c = codigo == null ? "" : codigo.trim().toUpperCase();
        List<Map<String, Object>> l = jdbc.queryForList("select id_vehiculo, marca, modelo, placas, color, id_persona_responsable, responsable_nombre from vehiculos where activo and upper(codigo_qr) = ?", c);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese QR no es de ningún vehículo de DOZZER");
        Map<String, Object> m = new LinkedHashMap<>(l.get(0));
        m.put("es_mio", Objects.equals(num(m.get("id_persona_responsable")), idPersona(usuarioActual())));
        m.put("qr", c);
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> pitstop(Long id, String qr) {
        Map<String, Object> v = vehiculoParaRevisar(id, qr);
        LocalDate hoy = LocalDate.now(MX);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id_vehiculo", id);
        r.put("marca", v.get("marca")); r.put("modelo", v.get("modelo")); r.put("placas", v.get("placas")); r.put("color", v.get("color"));
        Integer km = entero(v.get("km_actual"));
        r.put("km_anterior", km);
        r.put("km_semana", kmPorSemana(id));
        Integer hecha = jdbc.queryForObject("select count(*) from vehiculo_revisiones where id_vehiculo = ? and semana = ?", Integer.class, id, semana(LocalDateTime.now(MX)));
        r.put("ya_esta_semana", hecha != null && hecha > 0);
        // Preguntas que cambian cada semana según lo que toca revisar
        List<Map<String, Object>> preguntas = new ArrayList<>();
        Integer skm = entero(v.get("servicio_km"));
        int cada = Optional.ofNullable(entero(v.get("servicio_cada_km"))).orElse(10000);
        if (km != null && skm != null && km - skm >= cada * 0.75) {
            preguntas.add(Map.of("campo", "aceite", "texto", "Llevas " + fmt(km - skm) + " km sin cambio de aceite. Revisa bien este nivel."));
        }
        List<Map<String, Object>> ult = jdbc.queryForList("select datos from vehiculo_revisiones where id_vehiculo = ? order by fecha desc limit 1", id);
        if (!ult.isEmpty()) {
            Map<?, ?> dat = leer(ult.get(0).get("datos"));
            if (dat.get("llantas") instanceof Map<?, ?> ll) for (Map.Entry<?, ?> e : ll.entrySet()) {
                if (!"ok".equals(String.valueOf(e.getValue()))) preguntas.add(Map.of("campo", "llanta_" + e.getKey(), "texto", "La semana pasada reportaste esta llanta. ¿Ya se arregló?"));
            }
            if (dat.get("niveles") instanceof Map<?, ?> nv) for (Map.Entry<?, ?> e : nv.entrySet()) {
                Integer val = entero(e.getValue());
                if (val != null && val < 40 && !"aceite".equals(String.valueOf(e.getKey()))) preguntas.add(Map.of("campo", String.valueOf(e.getKey()), "texto", "La semana pasada estaba bajo. ¿Ya se rellenó?"));
            }
        }
        r.put("preguntas", preguntas);
        r.put("hoy", hoy.toString());
        return r;
    }

    public Map<String, Object> registrarRevision(Long id, String qr, Integer km, Integer segundos, String datosJson, byte[] foto, String tipoFoto) {
        Map<String, Object> v = vehiculoParaRevisar(id, qr);
        Usuario u = usuarioActual();
        LocalDateTime ahora = LocalDateTime.now(MX);
        if (km == null || km < 0 || km > 3_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe el kilometraje del tablero");
        Integer anterior = entero(v.get("km_actual"));
        if (anterior != null && km < anterior) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El kilometraje no puede ser menor que el último reportado (" + fmt(anterior) + " km)");
        Map<?, ?> datos = leer(datosJson);
        if (datos.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Faltan los datos del Pit Stop");

        // ---- Qué está mal (el sistema lo convierte en pendientes) ----
        List<String[]> problemas = new ArrayList<>();   // {titulo, gravedad}
        Map<String, String> nombresLlanta = Map.of("di", "delantera izquierda", "dd", "delantera derecha", "ti", "trasera izquierda", "td", "trasera derecha");
        if (datos.get("llantas") instanceof Map<?, ?> ll) for (Map.Entry<?, ?> e : ll.entrySet()) {
            String est = String.valueOf(e.getValue()), n = nombresLlanta.getOrDefault(String.valueOf(e.getKey()), String.valueOf(e.getKey()));
            if ("baja".equals(est)) problemas.add(new String[]{"Llanta " + n + " baja", "leve"});
            else if ("mal".equals(est)) problemas.add(new String[]{"Llanta " + n + " en mal estado", "grave"});
        }
        Map<String, String> nombresLuz = Map.of("fi", "faro izquierdo", "fd", "faro derecho", "ti", "calavera izquierda", "td", "calavera derecha");
        if (datos.get("luces") instanceof Map<?, ?> lu) for (Map.Entry<?, ?> e : lu.entrySet()) {
            if ("falla".equals(String.valueOf(e.getValue()))) problemas.add(new String[]{"Falla en " + nombresLuz.getOrDefault(String.valueOf(e.getKey()), "una luz"), "leve"});
        }
        Map<String, String> nombresNivel = Map.of("aceite", "Aceite de motor", "frenos", "Líquido de frenos", "direccion", "Líquido de dirección", "limpia", "Limpiaparabrisas");
        if (datos.get("niveles") instanceof Map<?, ?> nv) for (Map.Entry<?, ?> e : nv.entrySet()) {
            Integer val = entero(e.getValue());
            String k = String.valueOf(e.getKey());
            if (val != null && val < 35) problemas.add(new String[]{nombresNivel.getOrDefault(k, k) + " bajo", Set.of("aceite", "frenos").contains(k) && val < 20 ? "grave" : "leve"});
        }
        Map<String, String> nombresTestigo = Map.of("motor", "Check engine", "aceite", "Presión de aceite", "bateria", "Batería", "temperatura", "Temperatura del motor",
                "frenos", "Frenos", "abs", "ABS", "bolsa", "Bolsa de aire", "llantas", "Presión de llantas");
        Set<String> testigosGraves = Set.of("aceite", "temperatura", "frenos", "motor");
        if (datos.get("testigos") instanceof List<?> ts) for (Object t : ts) {
            String k = String.valueOf(t);
            problemas.add(new String[]{"Testigo encendido: " + nombresTestigo.getOrDefault(k, k), testigosGraves.contains(k) ? "grave" : "leve"});
        }
        boolean golpe = datos.get("golpe") instanceof Map<?, ?> g && Boolean.TRUE.equals(g.get("hay"));
        if (golpe) {
            if (foto == null || foto.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Toma una foto del golpe");
            String nota = datos.get("golpe") instanceof Map<?, ?> g2 && g2.get("nota") != null ? corto(String.valueOf(g2.get("nota")).trim(), 120) : null;
            problemas.add(new String[]{"Golpe o daño en la carrocería" + (nota != null && !nota.isBlank() ? ": " + nota : ""), "leve"});
        }

        // ---- ¿Se ve raro? (no se rechaza: se le avisa al administrador) ----
        List<String> sospechas = new ArrayList<>();
        Integer porSemana = kmPorSemana(id);
        int recorridos = anterior == null ? 0 : km - anterior;
        if (segundos != null && segundos < 10) sospechas.add("llenado en " + segundos + " segundos");
        if (anterior != null && porSemana != null && porSemana > 0 && recorridos > Math.max(porSemana * 4, 2500)) sospechas.add("salto de " + fmt(recorridos) + " km (normal: ~" + fmt(porSemana) + ")");
        if (anterior != null && recorridos == 0) {
            List<Integer> prev = jdbc.queryForList("select km_recorridos from vehiculo_revisiones where id_vehiculo = ? order by fecha desc limit 2", Integer.class, id);
            if (prev.size() == 2 && prev.stream().allMatch(x -> x != null && x == 0)) sospechas.add("el kilometraje no cambia en 3 semanas");
        }
        String sospecha = sospechas.isEmpty() ? null : corto(String.join(", ", sospechas), 300);

        // ---- Puntos: se ganan por reportar a tiempo; reportar problemas nunca quita ----
        Long per = idPersona(u);
        String sem = semana(ahora);
        Integer yaSemana = per == null ? 1 : jdbc.queryForObject("select count(*) from vehiculo_revisiones where id_persona = ? and semana = ?", Integer.class, per, sem);
        int racha = per == null ? 0 : racha(per, sem, true);
        int puntos = (yaSemana != null && yaSemana == 0) ? 10 + (racha >= 4 ? 5 : 0) : 0;

        String fotoKey = null;
        if (foto != null && foto.length > 0) {
            if (foto.length > 8 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La foto es demasiado grande");
            fotoKey = fotoStorage.guardar("vehiculos/" + id + "-" + UUID.randomUUID().toString().substring(0, 8), foto,
                    tipoFoto != null && tipoFoto.startsWith("image/") ? tipoFoto : "image/jpeg");
        }
        String datosTxt;
        try { datosTxt = JSON.writeValueAsString(datos); } catch (Exception e) { datosTxt = "{}"; }
        final String fk = fotoKey, dt = datosTxt;
        final int pts = puntos;
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement("insert into vehiculo_revisiones (id_vehiculo, id_usuario, id_persona, quien, fecha, semana, km, km_recorridos, datos, problemas, segundos, puntos, sospecha, calificacion, foto_key) " +
                    "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", new String[]{"id_revision"});
            ps.setLong(1, id); ps.setLong(2, u.getIdUsuario()); ps.setObject(3, per); ps.setString(4, corto(nombreDe(u), 180));
            ps.setTimestamp(5, Timestamp.valueOf(ahora)); ps.setString(6, sem); ps.setInt(7, km); ps.setObject(8, anterior == null ? null : recorridos);
            ps.setString(9, dt); ps.setInt(10, problemas.size()); ps.setObject(11, segundos); ps.setInt(12, pts); ps.setString(13, sospecha);
            ps.setString(14, sospecha == null ? "OK" : "REVISAR"); ps.setString(15, fk);
            return ps;
        }, kh);
        Long idRev = kh.getKey().longValue();
        jdbc.update("update vehiculos set km_actual = ?, km_fecha = ? where id_vehiculo = ?", km, Timestamp.valueOf(ahora), id);

        // Pendientes nuevos (sin repetir los que ya están abiertos)
        int nuevos = 0;
        for (String[] p : problemas) {
            Integer ya = jdbc.queryForObject("select count(*) from vehiculo_pendientes where id_vehiculo = ? and titulo = ? and estado in ('ABIERTO','EN_TALLER')", Integer.class, id, corto(p[0], 200));
            if (ya != null && ya > 0) continue;
            jdbc.update("insert into vehiculo_pendientes (id_vehiculo, tipo, titulo, estado, id_revision, km, fecha_creacion) values (?,?,?,?,?,?,?)",
                    id, "grave".equals(p[1]) ? "PROBLEMA_GRAVE" : "PROBLEMA", corto(p[0], 200), "ABIERTO", idRev, km, Timestamp.valueOf(ahora));
            nuevos++;
        }
        if (nuevos > 0) avisarAdmins("🔧 " + Objects.toString(v.get("placas"), "") + ": " + problemas.get(0)[0] + (problemas.size() > 1 ? " y " + (problemas.size() - 1) + " más" : ""),
                "Reportó " + nombreDe(u) + ". Toca para decidir.", "pendiente-" + id);

        LocalDate hoy = ahora.toLocalDate();
        Map<String, Object> estado = resumen(vehiculo(id), hoy, false);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("salud", estado.get("salud"));
        r.put("semaforo", estado.get("semaforo"));
        r.put("puntos", pts);
        r.put("problemas", problemas.size());
        r.put("km_recorridos", anterior == null ? null : recorridos);
        if (per != null) r.put("yo", marcador(per, hoy));
        return r;
    }

    // ===================== Racha, confianza, nivel y podio =====================

    private int racha(Long idPersona, String semanaActual, boolean incluirActual) {
        Set<String> semanas = new HashSet<>(jdbc.queryForList("select distinct semana from vehiculo_revisiones where id_persona = ? and coalesce(calificacion,'OK') <> 'FALSA'", String.class, idPersona));
        if (incluirActual) semanas.add(semanaActual);
        LocalDate d = LocalDate.now(MX);
        // Si esta semana aún no reporta, la racha se cuenta desde la semana pasada (todavía tiene tiempo)
        if (!semanas.contains(semana(d.atStartOfDay()))) d = d.minusWeeks(1);
        int n = 0;
        while (semanas.contains(semana(d.atStartOfDay())) && n < 520) { n++; d = d.minusWeeks(1); }
        return n;
    }

    private Map<String, Object> marcador(Long idPersona, LocalDate hoy) {
        Map<String, Object> m = new LinkedHashMap<>();
        int racha = racha(idPersona, semana(hoy.atStartOfDay()), false);
        Integer total = jdbc.queryForObject("select coalesce(sum(puntos),0) from vehiculo_revisiones where id_persona = ?", Integer.class, idPersona);
        Integer mes = jdbc.queryForObject("select coalesce(sum(puntos),0) from vehiculo_revisiones where id_persona = ? and fecha >= ?", Integer.class, idPersona,
                Timestamp.valueOf(hoy.withDayOfMonth(1).atStartOfDay()));
        Integer hecha = jdbc.queryForObject("select count(*) from vehiculo_revisiones where id_persona = ? and semana = ?", Integer.class, idPersona, semana(LocalDateTime.now(MX)));
        m.put("racha", racha);
        m.put("puntos_total", total);
        m.put("puntos_mes", mes);
        m.put("nivel", nivel(total == null ? 0 : total));
        m.put("confianza", confianza(idPersona));
        m.put("hecho_esta_semana", hecha != null && hecha > 0);
        List<Map<String, Object>> podio = podioLista(hoy);
        for (int i = 0; i < podio.size(); i++) if (Objects.equals(num(podio.get(i).get("id_persona")), idPersona)) { m.put("lugar", i + 1); break; }
        m.put("participantes", podio.size());
        return m;
    }

    private int confianza(Long idPersona) {
        LocalDateTime desde = LocalDateTime.now(MX).minusMonths(6);
        Integer falsas = jdbc.queryForObject("select count(*) from vehiculo_revisiones where id_persona = ? and calificacion = 'FALSA' and fecha >= ?", Integer.class, idPersona, Timestamp.valueOf(desde));
        Integer contra = jdbc.queryForObject("select count(*) from vehiculo_revisiones where id_persona = ? and contradicha and fecha >= ?", Integer.class, idPersona, Timestamp.valueOf(desde));
        Integer revisar = jdbc.queryForObject("select count(*) from vehiculo_revisiones where id_persona = ? and calificacion = 'REVISAR'", Integer.class, idPersona);
        return Math.max(0, 100 - 25 * nz(falsas) - 15 * nz(contra) - 5 * nz(revisar));
    }

    private static String nivel(int total) {
        return total >= 400 ? "Oro" : total >= 150 ? "Plata" : "Bronce";
    }

    private List<Map<String, Object>> podioLista(LocalDate hoy) {
        return jdbc.queryForList("select v.id_persona_responsable id_persona, max(v.responsable_nombre) nombre, " +
                "coalesce((select sum(r.puntos) from vehiculo_revisiones r where r.id_persona = v.id_persona_responsable and r.fecha >= ?), 0) puntos " +
                "from vehiculos v where v.activo and v.id_persona_responsable is not null group by v.id_persona_responsable order by puntos desc, nombre",
                Timestamp.valueOf(hoy.withDayOfMonth(1).atStartOfDay()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> podio() {
        usuarioActual();
        LocalDate hoy = LocalDate.now(MX);
        Long yo = idPersona(usuarioActual());
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Map<String, Object> x : podioLista(hoy)) {
            Long p = num(x.get("id_persona"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nombre", x.get("nombre"));
            m.put("puntos", x.get("puntos"));
            m.put("racha", racha(p, semana(hoy.atStartOfDay()), false));
            Integer total = jdbc.queryForObject("select coalesce(sum(puntos),0) from vehiculo_revisiones where id_persona = ?", Integer.class, p);
            m.put("nivel", nivel(nz(total)));
            m.put("confianza", confianza(p));
            m.put("yo", Objects.equals(p, yo));
            lista.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mes", hoy.getMonthValue());
        r.put("anio", hoy.getYear());
        r.put("lista", lista);
        return r;
    }

    // ===================== Avisos automáticos =====================

    // Cada mañana: vencimientos a 30, 7, 1 y 0 días (los vencidos se recuerdan los lunes)
    @Scheduled(cron = "0 5 8 * * *", zone = "America/Mexico_City")
    public void avisarVencimientos() {
        try {
            LocalDate hoy = LocalDate.now(MX);
            boolean lunes = hoy.getDayOfWeek() == DayOfWeek.MONDAY;
            List<String> lineas = new ArrayList<>();
            for (Map<String, Object> v : jdbc.queryForList("select placas, modelo, poliza_vigencia, proxima_verificacion from vehiculos where activo")) {
                for (String[] k : new String[][]{{"poliza_vigencia", "seguro"}, {"proxima_verificacion", "verificación"}}) {
                    LocalDate f = fecha(v.get(k[0]));
                    if (f == null) continue;
                    long d = ChronoUnit.DAYS.between(hoy, f);
                    if (HITOS.contains(d) || (d < 0 && lunes)) lineas.add(v.get("placas") + " " + k[1] + " (" + (d < 0 ? "venció hace " + dias(-d) : d == 0 ? "hoy" : "en " + dias(d)) + ")");
                }
            }
            if (!lineas.isEmpty()) avisarAdmins("🚗 " + lineas.size() + (lineas.size() == 1 ? " vencimiento en la flota" : " vencimientos en la flota"),
                    String.join(" · ", lineas.subList(0, Math.min(3, lineas.size()))) + (lineas.size() > 3 ? " y " + (lineas.size() - 3) + " más" : ""), "flota-" + hoy);
        } catch (Exception e) { log.warn("No se pudieron preparar los avisos de la flota: {}", e.getMessage()); }
    }

    // Lunes: recordatorio del Pit Stop. Jueves: solo a quien no lo ha hecho.
    @Scheduled(cron = "0 0 9 * * MON,THU", zone = "America/Mexico_City")
    public void recordarPitStop() {
        try {
            String sem = semana(LocalDateTime.now(MX));
            Set<Long> ids = new HashSet<>(jdbc.queryForList("select distinct u.id_usuario from vehiculos v join usuarios u on u.id_persona = v.id_persona_responsable and u.activo " +
                    "where v.activo and not exists (select 1 from vehiculo_revisiones r where r.id_persona = v.id_persona_responsable and r.semana = ?)", Long.class, sem));
            if (!ids.isEmpty()) push.enviarDespues(ids, "🏁 Pit Stop semanal", "Revisa tu vehículo: 30 segundos y sumas puntos para el podio.", "/?ir=pitstop", "pitstop-" + sem);
        } catch (Exception e) { log.warn("No se pudo recordar el Pit Stop: {}", e.getMessage()); }
    }

    private void avisarAdmins(String titulo, String cuerpo, String etiqueta) {
        Set<Long> flota = new HashSet<>(jdbc.queryForList("select distinct ur.id_usuario from usuario_roles ur join roles r on r.id = ur.id_rol join usuarios u on u.id_usuario = ur.id_usuario where r.nombre = 'FLOTA' and u.activo", Long.class));
        // Si nadie administra la flota, avisa a los administradores generales
        if (flota.isEmpty()) flota = new HashSet<>(jdbc.queryForList("select distinct ur.id_usuario from usuario_roles ur join roles r on r.id = ur.id_rol join usuarios u on u.id_usuario = ur.id_usuario where r.nombre = 'ADMIN' and u.activo", Long.class));
        if (!flota.isEmpty()) push.enviarDespues(flota, titulo, cuerpo, "/?ir=flota", etiqueta);
    }

    // ===================== Utilidades =====================

    private void evento(Long id, String tipo, String texto, String quien) {
        jdbc.update("insert into vehiculo_historial (id_vehiculo, fecha, tipo, texto, quien) values (?,?,?,?,?)", id, ahora(), tipo, corto(texto, 500), corto(quien, 180));
    }

    private static String semana(LocalDateTime f) {
        return f.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", f.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private static Timestamp ahora() { return Timestamp.valueOf(LocalDateTime.now(MX)); }

    private static Map<?, ?> leer(Object json) {
        if (json == null) return Map.of();
        try { Object o = JSON.readValue(String.valueOf(json), Object.class); return o instanceof Map<?, ?> m ? m : Map.of(); }
        catch (Exception e) { return Map.of(); }
    }

    private static String trabajos(Object o) {
        if (o == null) return null;
        Set<String> validos = Set.of("Servicio", "Aceite", "Frenos", "Llantas", "Afinación", "Luces", "Batería", "Suspensión", "Carrocería", "Otro");
        List<String> l = new ArrayList<>();
        if (o instanceof Collection<?> c) for (Object x : c) { String s = String.valueOf(x); if (validos.contains(s)) l.add(s); }
        return l.isEmpty() ? null : String.join(", ", l);
    }

    private static String temporal() {
        String letras = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz", nums = "23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder("Dz");
        for (int i = 0; i < 4; i++) b.append(letras.charAt(r.nextInt(letras.length())));
        for (int i = 0; i < 4; i++) b.append(nums.charAt(r.nextInt(nums.length())));
        return b.toString();
    }

    private static String primerNombre(String n) {
        if (n == null || n.isBlank()) return "";
        return Arrays.stream(n.trim().toLowerCase().split("\\s+")).map(w -> w.substring(0, 1).toUpperCase() + w.substring(1)).collect(Collectors.joining(" "));
    }

    // "RAYMUNDO VILCHIS MUÑOZ" y "VILCHIS MUÑOZ RAYMUNDO" son la misma persona
    private static String firma(String nombre) {
        return Arrays.stream(norm(nombre).split("\\s+")).filter(s -> !s.isBlank()).sorted().collect(Collectors.joining(" "));
    }

    private static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase().replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String placa(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase().replaceAll("\\s+", "");
        return t.isEmpty() ? null : corto(t, 20);
    }

    private static String economico(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase().replace('.', '-');
        return t.isEmpty() || t.equals("S/N") || t.equals("SN") ? null : corto(t, 20);
    }

    private static String txt(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static String corto(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static Long num(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    private static Long largo(Object o) { return num(txt(o)); }

    private static Integer entero(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { String s = String.valueOf(o).trim().replace(",", ""); return s.isEmpty() ? null : (int) Math.round(Double.parseDouble(s)); } catch (Exception e) { return null; }
    }

    private static int nz(Integer i) { return i == null ? 0 : i; }

    private static LocalDate fecha(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        if (o instanceof Timestamp t) return t.toLocalDateTime().toLocalDate();
        try { return LocalDate.parse(String.valueOf(o).trim().substring(0, 10)); } catch (Exception e) { return null; }
    }

    private static java.sql.Date sqlFecha(Object o) {
        LocalDate d = fecha(txt(o));
        return d == null ? null : java.sql.Date.valueOf(d);
    }

    private static LocalDateTime fechaHora(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp t) return t.toLocalDateTime();
        if (o instanceof LocalDateTime t) return t;
        try { return LocalDateTime.parse(String.valueOf(o).replace(' ', 'T')); } catch (Exception e) { return null; }
    }

    private static String dias(long d) { return d == 1 ? "1 día" : d + " días"; }

    private static String fmt(Integer n) { return n == null ? "—" : String.format(Locale.US, "%,d", n); }

    private static final String[] MESES = {"ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"};

    private static String texto(LocalDate d) {
        return d == null ? "—" : d.getDayOfMonth() + " " + MESES[d.getMonthValue() - 1] + " " + d.getYear();
    }
}

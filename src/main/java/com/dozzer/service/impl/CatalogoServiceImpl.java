package com.dozzer.service.impl;

import com.dozzer.entity.*;
import com.dozzer.repository.*;
import com.dozzer.service.CatalogoService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
@Transactional
public class CatalogoServiceImpl implements CatalogoService {

    @Autowired private SedeRepository sedeRepository;
    @Autowired private UbicacionRepository ubicacionRepository;
    @Autowired private PuestoDepartamentoRepository puestoRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private ClasificacionRepository clasificacionRepository;
    @Autowired private MarcaRepository marcaRepository;
    @Autowired private ColorRepository colorRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private DepartamentoRepository departamentoRepository;
    @Autowired private EntidadRepository entidadRepository;
    @Autowired private TipoUbicacionRepository tipoUbicacionRepository;
    @Autowired private SubcategoriaRepository subcategoriaRepository;

    // =========================================================
    // LISTAR
    // =========================================================

    @Override
    @Transactional(readOnly = true)
    public List<Map<String,Object>> listar(String catalogo) {
        List<Map<String,Object>> resultado = new ArrayList<>();
        switch(catalogo) {
            case "sedes" -> {
                Map<String,String> entidades = nombresEntidades();
                sedeRepository.findAll().forEach(s -> resultado.add(convertirSede(s, entidades)));
            }
            case "ubicaciones" -> {
                Map<String,String> tipos = nombresTiposUbicacion();
                ubicacionRepository.findAll().forEach(u -> resultado.add(convertirUbicacion(u, tipos)));
            }
            case "departamentos" -> departamentoRepository.findAll().forEach(d -> resultado.add(convertirDepartamento(d)));
            case "puestos" -> puestoRepository.findAll().forEach(p -> resultado.add(convertirPuesto(p)));
            case "personas" -> personaRepository.findAll().forEach(p -> resultado.add(convertirPersona(p)));
            case "entidades" -> entidadRepository.findAll().forEach(e -> resultado.add(convertirEntidad(e)));
            case "tipos-ubicacion" -> tipoUbicacionRepository.findAll().forEach(t -> resultado.add(convertirTipoUbicacion(t)));
            case "clasificaciones" -> clasificacionRepository.findAll().forEach(c -> resultado.add(convertirClasificacion(c)));
            case "subcategorias" -> subcategoriaRepository.findAll().forEach(s -> resultado.add(convertirSubcategoria(s)));
            case "marcas" -> marcaRepository.findAll().forEach(m -> resultado.add(convertirMarca(m)));
            case "colores" -> colorRepository.findAll().forEach(c -> resultado.add(convertirColor(c)));
            default -> throw catalogoNoValido();
        }

        // Un empleado solo ve las sedes, ubicaciones y personas de sus sedes asignadas
        Usuario usuario = usuarioActual();
        boolean admin = usuario.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getNombre()));
        if (!admin && ("sedes".equals(catalogo) || "ubicaciones".equals(catalogo) || "personas".equals(catalogo))) {
            Set<Long> idsSedes = new HashSet<>();
            usuario.getSedes().forEach(s -> idsSedes.add(s.getIdSede()));
            resultado.removeIf(fila -> {
                Object valor = fila.get("id_sede");
                return valor == null || !idsSedes.contains(((Number) valor).longValue());
            });
        }

        String campoId = obtenerCampoId(catalogo);
        resultado.sort((a,b) -> Long.compare(((Number)b.get(campoId)).longValue(),((Number)a.get(campoId)).longValue()));
        return resultado;
    }

    // =========================================================
    // CREAR
    // =========================================================

    @Override
    public Map<String,Object> crear(String catalogo,Map<String,Object> datos) {
        validarDatos(datos);
        validarRequeridos(catalogo, datos, true);
        switch(catalogo) {
            case "sedes" -> { Sede s = new Sede(); asignarSede(s,datos); sedeRepository.save(s); }
            case "ubicaciones" -> { Ubicacion u = new Ubicacion(); asignarUbicacion(u,datos); ubicacionRepository.save(u); }
            case "departamentos" -> { Departamento d = new Departamento(); asignarDepartamento(d,datos); departamentoRepository.save(d); }
            case "puestos" -> { PuestoDepartamento p = new PuestoDepartamento(); asignarPuesto(p,datos); puestoRepository.save(p); }
            case "personas" -> { Persona p = new Persona(); asignarPersona(p,datos); personaRepository.save(p); }
            case "entidades" -> { Entidad e = new Entidad(); asignarEntidad(e,datos); entidadRepository.save(e); }
            case "tipos-ubicacion" -> { TipoUbicacion t = new TipoUbicacion(); asignarTipoUbicacion(t,datos); tipoUbicacionRepository.save(t); }
            case "clasificaciones" -> { Clasificacion c = new Clasificacion(); asignarClasificacion(c,datos); clasificacionRepository.save(c); }
            case "subcategorias" -> { Subcategoria s = new Subcategoria(); asignarSubcategoria(s,datos); subcategoriaRepository.save(s); }
            case "marcas" -> { Marca m = new Marca(); asignarMarca(m,datos); marcaRepository.save(m); }
            case "colores" -> { Color c = new Color(); asignarColor(c,datos); colorRepository.save(c); }
            default -> throw catalogoNoValido();
        }
        guardarCambios();
        return Map.of("mensaje","Registro creado correctamente");
    }

    // =========================================================
    // ACTUALIZAR
    // =========================================================

    @Override
    public Map<String,Object> actualizar(String catalogo,Long id,Map<String,Object> datos) {
        validarDatos(datos);
        validarRequeridos(catalogo, datos, false);
        switch(catalogo) {
            case "sedes" -> { Sede s = sedeRepository.findById(id).orElseThrow(this::registroNoEncontrado); asignarSede(s,datos); }
            case "ubicaciones" -> { Ubicacion u = ubicacionRepository.findById(id).orElseThrow(this::registroNoEncontrado); asignarUbicacion(u,datos); }
            case "departamentos" -> { Departamento d = departamentoRepository.findById(id).orElseThrow(this::registroNoEncontrado); asignarDepartamento(d,datos); }
            case "puestos" -> { PuestoDepartamento p = puestoRepository.findById(id).orElseThrow(this::registroNoEncontrado); asignarPuesto(p,datos); }
            case "personas" -> { Persona p = personaRepository.findById(id).orElseThrow(this::registroNoEncontrado); asignarPersona(p,datos); }
            case "entidades" -> { Entidad e = entidadRepository.findById(id).orElseThrow(this::registroNoEncontrado); asignarEntidad(e,datos); }
            case "tipos-ubicacion" -> { TipoUbicacion t = tipoUbicacionRepository.findById(id).orElseThrow(this::registroNoEncontrado); asignarTipoUbicacion(t,datos); }
            case "clasificaciones" -> { Clasificacion c = clasificacionRepository.findById(id).orElseThrow(this::registroNoEncontrado); asignarClasificacion(c,datos); }
            case "subcategorias" -> { Subcategoria s = subcategoriaRepository.findById(id).orElseThrow(this::registroNoEncontrado); asignarSubcategoria(s,datos); }
            case "marcas" -> { Marca m = marcaRepository.findById(id).orElseThrow(this::registroNoEncontrado); asignarMarca(m,datos); }
            case "colores" -> { Color c = colorRepository.findById(id).orElseThrow(this::registroNoEncontrado); asignarColor(c,datos); }
            default -> throw catalogoNoValido();
        }
        guardarCambios();
        return Map.of("mensaje","Registro actualizado correctamente");
    }

    // =========================================================
    // ELIMINAR (desactiva los catálogos con estatus; elimina el resto si no está en uso)
    // =========================================================

    @Override
    public Map<String,Object> eliminar(String catalogo,Long id) {
        switch(catalogo) {
            case "sedes" -> sedeRepository.findById(id).orElseThrow(this::registroNoEncontrado).setEstatus(false);
            case "ubicaciones" -> ubicacionRepository.findById(id).orElseThrow(this::registroNoEncontrado).setEstatus(false);
            case "departamentos" -> departamentoRepository.findById(id).orElseThrow(this::registroNoEncontrado).setEstatus(false);
            case "puestos" -> puestoRepository.findById(id).orElseThrow(this::registroNoEncontrado).setEstatus(false);
            case "personas" -> personaRepository.findById(id).orElseThrow(this::registroNoEncontrado).setEstatus(false);
            case "entidades" -> entidadRepository.findById(id).orElseThrow(this::registroNoEncontrado).setEstatus(false);
            case "tipos-ubicacion" -> tipoUbicacionRepository.findById(id).orElseThrow(this::registroNoEncontrado).setEstatus(false);
            case "clasificaciones" -> clasificacionRepository.delete(clasificacionRepository.findById(id).orElseThrow(this::registroNoEncontrado));
            case "subcategorias" -> subcategoriaRepository.delete(subcategoriaRepository.findById(id).orElseThrow(this::registroNoEncontrado));
            case "marcas" -> marcaRepository.delete(marcaRepository.findById(id).orElseThrow(this::registroNoEncontrado));
            case "colores" -> colorRepository.delete(colorRepository.findById(id).orElseThrow(this::registroNoEncontrado));
            default -> throw catalogoNoValido();
        }
        try {
            guardarCambios();
        } catch (ResponseStatusException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede eliminar porque hay bienes u otros registros que lo utilizan");
        }
        return Map.of("mensaje", tieneEstatus(catalogo) ? "Registro desactivado correctamente" : "Registro eliminado correctamente");
    }

    // =========================================================
    // ASIGNACIÓN DE DATOS
    // =========================================================

    private void asignarSede(Sede s,Map<String,Object> d) {
        // La entidad se guarda como el id de cat_entidades en texto (formato existente)
        if(d.containsKey("entidad")) s.setEntidad(texto(d,"entidad"));
        if(d.containsKey("nombre_sede")) s.setNombreSede(texto(d,"nombre_sede"));
        if(d.containsKey("prefijo_clave")) s.setPrefijoClave(texto(d,"prefijo_clave"));
        if(d.containsKey("direccion_completa")) s.setDireccionCompleta(texto(d,"direccion_completa"));
        if(d.containsKey("estatus")) s.setEstatus(booleano(d,"estatus"));
    }

    private void asignarUbicacion(Ubicacion u,Map<String,Object> d) {
        if(d.containsKey("id_sede")) {
            Long id = numero(d,"id_sede");
            u.setSede(id != null ? sedeRepository.getReferenceById(id) : null);
        }
        // El tipo se guarda como el id de cat_tipos_ubicacion en texto (formato existente)
        if(d.containsKey("tipo_ubicacion")) u.setTipoUbicacion(texto(d,"tipo_ubicacion"));
        if(d.containsKey("nombre_area")) u.setNombreArea(texto(d,"nombre_area"));
        if(d.containsKey("estatus")) u.setEstatus(booleano(d,"estatus"));
    }

    private void asignarDepartamento(Departamento dep, Map<String,Object> d) {
        if(d.containsKey("nombre_departamento")) dep.setNombreDepartamento(texto(d,"nombre_departamento"));
        if(d.containsKey("estatus")) dep.setEstatus(booleano(d,"estatus"));
    }

    private void asignarPuesto(PuestoDepartamento p, Map<String,Object> d) {
        if (d.containsKey("id_departamento")) {
            Long id = numero(d, "id_departamento");
            p.setDepartamento(id != null ? departamentoRepository.getReferenceById(id) : null);
        }
        if (d.containsKey("nombre_puesto")) p.setNombrePuesto(texto(d, "nombre_puesto"));
        if (d.containsKey("estatus")) p.setEstatus(booleano(d, "estatus"));
    }

    private void asignarPersona(Persona p,Map<String,Object> d) {
        if(d.containsKey("nombre_completo")) p.setNombreCompleto(texto(d,"nombre_completo"));
        if(d.containsKey("correo")) {
            String correo = texto(d,"correo");
            if (correo != null && !correo.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingresa un correo electrónico válido");
            }
            p.setCorreo(correo != null ? correo.toLowerCase(Locale.ROOT) : null);
        }
        if(d.containsKey("numero_empleado")) p.setNumeroEmpleado(texto(d,"numero_empleado"));
        if(d.containsKey("id_puesto")) {
            Long id = numero(d,"id_puesto");
            p.setPuesto(id != null ? puestoRepository.getReferenceById(id) : null);
        }
        if(d.containsKey("id_sede")) {
            Long id = numero(d,"id_sede");
            p.setSede(id != null ? sedeRepository.getReferenceById(id) : null);
        }
        if(d.containsKey("estatus")) p.setEstatus(booleano(d,"estatus"));
    }

    private void asignarEntidad(Entidad e, Map<String,Object> d) {
        if(d.containsKey("nombre_entidad")) e.setNombreEntidad(texto(d,"nombre_entidad"));
        if(d.containsKey("abreviatura")) e.setAbreviatura(texto(d,"abreviatura"));
        if(d.containsKey("estatus")) e.setEstatus(Boolean.TRUE.equals(booleano(d,"estatus")));
    }

    private void asignarTipoUbicacion(TipoUbicacion t, Map<String,Object> d) {
        if(d.containsKey("nombre_tipo_ubicacion")) t.setNombreTipoUbicacion(texto(d,"nombre_tipo_ubicacion"));
        if(d.containsKey("es_temporal")) t.setEsTemporal(Boolean.TRUE.equals(booleano(d,"es_temporal")));
        if(d.containsKey("estatus")) t.setEstatus(Boolean.TRUE.equals(booleano(d,"estatus")));
    }

    private void asignarClasificacion(Clasificacion c,Map<String,Object> d) {
        if(d.containsKey("codigo")) c.setCodigo(texto(d,"codigo"));
        if(d.containsKey("nombre")) c.setNombre(texto(d,"nombre"));
    }

    private void asignarSubcategoria(Subcategoria s, Map<String,Object> d) {
        if(d.containsKey("id_clasificacion")) {
            Long id = numero(d,"id_clasificacion");
            s.setClasificacion(id != null ? clasificacionRepository.getReferenceById(id) : null);
        }
        if(d.containsKey("nombre_subcategoria")) s.setNombreSubcategoria(texto(d,"nombre_subcategoria"));
    }

    private void asignarMarca(Marca m,Map<String,Object> d) {
        if(d.containsKey("nombre_marca")) m.setNombreMarca(texto(d,"nombre_marca"));
    }

    private void asignarColor(Color c,Map<String,Object> d) {
        if(d.containsKey("nombre_color")) c.setNombreColor(texto(d,"nombre_color"));
    }

    // =========================================================
    // CONVERSIÓN A RESPUESTA
    // =========================================================

    private Map<String,Object> convertirSede(Sede s, Map<String,String> entidades) {
        Map<String,Object> m = new HashMap<>();
        m.put("id_sede",s.getIdSede());
        m.put("entidad",s.getEntidad());
        m.put("nombre_entidad", s.getEntidad() != null ? entidades.get(s.getEntidad().trim()) : null);
        m.put("nombre_sede",s.getNombreSede());
        m.put("prefijo_clave",s.getPrefijoClave());
        m.put("direccion_completa",s.getDireccionCompleta());
        m.put("estatus",s.getEstatus());
        return m;
    }

    private Map<String,Object> convertirUbicacion(Ubicacion u, Map<String,String> tipos) {
        Map<String,Object> m = new HashMap<>();
        m.put("id_ubicacion",u.getIdUbicacion());
        m.put("id_sede",u.getSede() != null ? u.getSede().getIdSede() : null);
        m.put("nombre_sede",u.getSede() != null ? u.getSede().getNombreSede() : null);
        m.put("tipo_ubicacion",u.getTipoUbicacion());
        m.put("nombre_tipo_ubicacion", u.getTipoUbicacion() != null ? tipos.get(u.getTipoUbicacion().trim()) : null);
        m.put("nombre_area",u.getNombreArea());
        m.put("estatus",u.getEstatus());
        return m;
    }

    private Map<String,Object> convertirDepartamento(Departamento d) {
        Map<String,Object> m = new HashMap<>();
        m.put("id_departamento", d.getIdDepartamento());
        m.put("nombre_departamento", d.getNombreDepartamento());
        m.put("estatus", d.getEstatus());
        return m;
    }

    private Map<String,Object> convertirPuesto(PuestoDepartamento p) {
        Map<String,Object> m = new HashMap<>();
        m.put("id_puesto", p.getIdPuesto());
        m.put("id_departamento", p.getDepartamento() != null ? p.getDepartamento().getIdDepartamento() : null);
        m.put("nombre_departamento", p.getDepartamento() != null ? p.getDepartamento().getNombreDepartamento() : null);
        m.put("nombre_puesto", p.getNombrePuesto());
        m.put("estatus", p.getEstatus());
        return m;
    }

    private Map<String,Object> convertirPersona(Persona p) {
        Map<String,Object> m = new HashMap<>();
        m.put("id_persona",p.getIdPersona());
        m.put("nombre_completo",p.getNombreCompleto());
        m.put("correo",p.getCorreo());
        m.put("numero_empleado",p.getNumeroEmpleado());
        m.put("id_puesto",p.getPuesto() != null ? p.getPuesto().getIdPuesto() : null);
        m.put("nombre_puesto",p.getPuesto() != null ? p.getPuesto().getNombrePuesto() : null);
        m.put("id_sede",p.getSede() != null ? p.getSede().getIdSede() : null);
        m.put("nombre_sede",p.getSede() != null ? p.getSede().getNombreSede() : null);
        m.put("estatus",p.getEstatus());
        return m;
    }

    private Map<String,Object> convertirEntidad(Entidad e) {
        Map<String,Object> m = new HashMap<>();
        m.put("id_entidad", e.getIdEntidad());
        m.put("nombre_entidad", e.getNombreEntidad());
        m.put("abreviatura", e.getAbreviatura());
        m.put("estatus", e.getEstatus());
        return m;
    }

    private Map<String,Object> convertirTipoUbicacion(TipoUbicacion t) {
        Map<String,Object> m = new HashMap<>();
        m.put("id_tipo_ubicacion", t.getIdTipoUbicacion());
        m.put("nombre_tipo_ubicacion", t.getNombreTipoUbicacion());
        m.put("es_temporal", t.getEsTemporal());
        m.put("estatus", t.getEstatus());
        return m;
    }

    private Map<String,Object> convertirClasificacion(Clasificacion c) {
        Map<String,Object> m = new HashMap<>();
        m.put("id_clasificacion",c.getIdClasificacion());
        m.put("codigo",c.getCodigo());
        m.put("nombre",c.getNombre());
        return m;
    }

    private Map<String,Object> convertirSubcategoria(Subcategoria s) {
        Map<String,Object> m = new HashMap<>();
        m.put("id_subcategoria", s.getIdSubcategoria());
        m.put("id_clasificacion", s.getClasificacion() != null ? s.getClasificacion().getIdClasificacion() : null);
        m.put("nombre_clasificacion", s.getClasificacion() != null ? s.getClasificacion().getNombre() : null);
        m.put("nombre_subcategoria", s.getNombreSubcategoria());
        return m;
    }

    private Map<String,Object> convertirMarca(Marca m) {
        Map<String,Object> r = new HashMap<>();
        r.put("id_marca",m.getIdMarca());
        r.put("nombre_marca",m.getNombreMarca());
        return r;
    }

    private Map<String,Object> convertirColor(Color c) {
        Map<String,Object> m = new HashMap<>();
        m.put("id_color",c.getIdColor());
        m.put("nombre_color",c.getNombreColor());
        return m;
    }

    private Map<String,String> nombresEntidades() {
        Map<String,String> mapa = new HashMap<>();
        entidadRepository.findAll().forEach(e -> mapa.put(String.valueOf(e.getIdEntidad()), e.getNombreEntidad()));
        return mapa;
    }

    private Map<String,String> nombresTiposUbicacion() {
        Map<String,String> mapa = new HashMap<>();
        tipoUbicacionRepository.findAll().forEach(t -> mapa.put(String.valueOf(t.getIdTipoUbicacion()), t.getNombreTipoUbicacion()));
        return mapa;
    }

    // =========================================================
    // VALIDACIONES Y UTILERÍAS
    // =========================================================

    // Campos obligatorios por catálogo: en alta deben venir; en edición, si vienen, no pueden ir vacíos
    private void validarRequeridos(String catalogo, Map<String,Object> d, boolean alta) {
        Map<String,String> requeridos = switch(catalogo) {
            case "sedes" -> Map.of("nombre_sede", "el nombre de la sede");
            case "ubicaciones" -> Map.of("id_sede", "la sede", "nombre_area", "el nombre del área");
            case "departamentos" -> Map.of("nombre_departamento", "el nombre del departamento");
            case "puestos" -> Map.of("nombre_puesto", "el nombre del puesto", "id_departamento", "el departamento");
            case "personas" -> Map.of("nombre_completo", "el nombre completo", "id_sede", "la sede");
            case "entidades" -> Map.of("nombre_entidad", "el nombre de la entidad", "abreviatura", "la abreviatura");
            case "tipos-ubicacion" -> Map.of("nombre_tipo_ubicacion", "el nombre del tipo de ubicación");
            case "clasificaciones" -> Map.of("nombre", "el nombre de la clasificación");
            case "subcategorias" -> Map.of("nombre_subcategoria", "el nombre de la subcategoría", "id_clasificacion", "la clasificación");
            case "marcas" -> Map.of("nombre_marca", "el nombre de la marca");
            case "colores" -> Map.of("nombre_color", "el nombre del color");
            default -> throw catalogoNoValido();
        };
        requeridos.forEach((campo, etiqueta) -> {
            if (!alta && !d.containsKey(campo)) return;
            Object valor = d.get(campo);
            if (valor == null || valor.toString().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica " + etiqueta);
            }
        });
    }

    private boolean tieneEstatus(String catalogo) {
        return Set.of("sedes", "ubicaciones", "departamentos", "puestos", "personas", "entidades", "tipos-ubicacion")
                .contains(catalogo);
    }

    // Aplica los cambios en la base para detectar duplicados o registros en uso
    private void guardarCambios() {
        try {
            sedeRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se pudo guardar: el registro ya existe o hace referencia a datos no válidos");
        }
    }

    private String texto(Map<String,Object> d,String campo) {
        Object v = d.get(campo);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private Long numero(Map<String,Object> d,String campo) {
        Object v = d.get(campo);
        if(v == null || v.toString().isBlank()) return null;
        try {
            return Long.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valor no válido en " + campo);
        }
    }

    private Boolean booleano(Map<String,Object> d,String campo) {
        Object v = d.get(campo);
        if(v == null) return null;
        return v instanceof Boolean ? (Boolean)v : Boolean.valueOf(v.toString());
    }

    private String obtenerCampoId(String c) {
        return switch(c) {
            case "sedes" -> "id_sede";
            case "ubicaciones" -> "id_ubicacion";
            case "departamentos" -> "id_departamento";
            case "puestos" -> "id_puesto";
            case "personas" -> "id_persona";
            case "entidades" -> "id_entidad";
            case "tipos-ubicacion" -> "id_tipo_ubicacion";
            case "clasificaciones" -> "id_clasificacion";
            case "subcategorias" -> "id_subcategoria";
            case "marcas" -> "id_marca";
            case "colores" -> "id_color";
            default -> throw catalogoNoValido();
        };
    }

    private Usuario usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        return usuarioRepository.findByCorreoIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }

    private void validarDatos(Map<String,Object> datos) {
        if(datos == null || datos.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No hay campos para guardar");
    }

    private ResponseStatusException catalogoNoValido() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Catálogo no válido");
    }

    private ResponseStatusException registroNoEncontrado() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Registro no encontrado");
    }
}

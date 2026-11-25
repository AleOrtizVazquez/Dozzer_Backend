
package com.dozzer.service.impl;

import com.dozzer.entity.*;
import com.dozzer.repository.*;
import com.dozzer.service.BienService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.text.Normalizer;
import java.util.*;

@Service
@Transactional
public class BienServiceImpl implements BienService {

    @Autowired
    private BienRepository bienRepository;
    @Autowired
    private ClaveBienService claveBienService;
    @Autowired
    private EtiquetaService etiquetaService;
    @Autowired
    private MovimientoBienService movimientoBienService;
    @Autowired
    private FotoStorageService fotoStorage;
    @Autowired
    private ClasificacionRepository clasificacionRepository;
    @Autowired
    private MarcaRepository marcaRepository;
    @Autowired
    private ColorRepository colorRepository;
    @Autowired
    private PersonaRepository personaRepository;
    @Autowired
    private UbicacionRepository ubicacionRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private ConfiguracionAutorizacionRepository configuracionAutorizacionRepository;
    @Autowired
    private SubcategoriaRepository subcategoriaRepository;
    @Autowired
    private SolicitudBienRepository solicitudBienRepository;
    @Autowired
    private DocumentoFormatoService documentoFormatoService;

    @Autowired
    private NotificacionService notificacionService;

    @Override
    @Transactional(readOnly = true)
    public List<Map<String,Object>> listarBienes() {
        List<Map<String,Object>> resultado = new ArrayList<>();
        Usuario usuario = usuarioActual();
        boolean admin = esAdmin(usuario);
        Set<Long> sedesPermitidas = idsSedes(usuario);
        for (Bien bien : bienRepository.findByActivoTrueOrderByIdBienDesc()) {
            Long idSede = obtenerIdSede(bien);
            if (admin || (idSede != null && sedesPermitidas.contains(idSede))) {
                resultado.add(convertirAMap(bien));
            }
        }
        return resultado;
    }

    private static final java.util.regex.Pattern FOTO_ALTA = java.util.regex.Pattern.compile("^(cld:v\\d+/)?[A-Za-z0-9_/-]{0,80}bienes/nuevo-[0-9a-f]{32}(\\.(jpg|png|webp))?$");

    private void validarFoto(byte[] foto, String tipo) {
        if (foto == null || foto.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No llegó la foto");
        if (foto.length > 8 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La foto es demasiado grande");
        if (tipo == null || !tipo.startsWith("image/")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo no es una imagen");
    }

    @Override
    public Map<String,Object> subirFotoAlta(byte[] foto, String tipo) {
        validarFoto(foto, tipo);
        String llave = fotoStorage.guardar("bienes/nuevo-" + UUID.randomUUID().toString().replace("-", ""), foto, tipo);
        Map<String,Object> r = new HashMap<>();
        r.put("fotoKey", llave);
        r.put("foto_mini", fotoStorage.urlMini(llave));
        return r;
    }

    @Override
    @Transactional
    public Map<String,Object> cambiarFoto(Long idBien, byte[] foto, String tipo) {
        validarFoto(foto, tipo);
        Bien bien = buscarBienActivo(idBien);
        validarAccesoSede(usuarioActual(), obtenerIdSede(bien));
        String anterior = bien.getFotoKey();
        String llave = fotoStorage.guardar("bienes/" + bien.getIdBien() + "-" + UUID.randomUUID().toString().substring(0, 8), foto, tipo);
        bien.setFotoKey(llave);
        bien.setFotoFecha(java.time.LocalDateTime.now());
        bienRepository.save(bien);
        if (anterior != null && !anterior.equals(llave)) fotoStorage.borrar(anterior);
        return convertirAMap(bien);
    }

    // Solo se aceptan llaves que generó subirFotoAlta (nadie puede ligar fotos ajenas)
    private void ponerFotoAlta(Bien bien, Map<String,Object> datos) {
        Object k = datos == null ? null : datos.get("fotoKey");
        if (k == null || k.toString().isBlank()) return;
        String llave = k.toString().trim();
        if (llave.length() > 300 || !FOTO_ALTA.matcher(llave).matches()) return;
        bien.setFotoKey(llave);
        bien.setFotoFecha(java.time.LocalDateTime.now());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String,Object> obtenerBien(Long idBien) {
        Bien bien = buscarBienActivo(idBien);
        validarAccesoSede(usuarioActual(), obtenerIdSede(bien));
        return convertirAMap(bien);
    }

    @Override
    public Map<String,Object> crearBien(Map<String,Object> datos) {
        Usuario usuario = usuarioActual();
        Long idSede = obtenerIdSedeDesdeDatos(datos, null);
        validarAccesoSede(usuario, idSede);
        if (!esAdmin(usuario) && requiereAutorizacion("ALTA")) {
            return registrarSolicitud("ALTA", null, datos, usuario, idSede);
        }
        String claveEscrita = obtenerTexto(datos, "clave");
        if (claveEscrita != null && !claveEscrita.isBlank() && bienRepository.existsByClave(claveEscrita.trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La clave " + claveEscrita.trim() + " ya existe; deja que se asigne sola o usa otra");
        }
        Bien bien = new Bien();
        asignarDatos(bien,datos);
        ponerFotoAlta(bien, datos);
        bien.setUsuarioRegistro(usuario);
        Bien guardado = bienRepository.saveAndFlush(bien);
        Map<String,Object> respuesta = convertirAMap(bienRepository.findById(guardado.getIdBien()).orElse(guardado));
        // Formato de alta registrado automáticamente
        Documento doc = documentoFormatoService.registrar("ALTA", guardado, null, usuario, usuario,
                "Alta registrada directamente por el administrador.");
        respuesta.put("idDocumento", doc.getIdDocumento());
        return respuesta;
    }

    @Override
    public Map<String,Object> actualizarBien(Long idBien,Map<String,Object> datos) {
        Usuario usuario = usuarioActual();
        Bien bien = buscarBienActivo(idBien);
        if (!esAdmin(usuario) && (datos == null || obtenerLong(datos, "idUbicacion") == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes seleccionar una ubicación de una sede asignada");
        }
        Long sedeActual = obtenerIdSede(bien);
        validarAccesoSede(usuario, sedeActual);
        Long sedePropuesta = obtenerIdSedeDesdeDatos(datos, sedeActual);
        validarAccesoSede(usuario, sedePropuesta);
        if (!esAdmin(usuario) && requiereAutorizacion("MODIFICACION")) {
            return registrarSolicitud("MODIFICACION", bien, datos, usuario, sedePropuesta);
        }
        MovimientoBienService.Foto antes = movimientoBienService.foto(bien);
        asignarDatos(bien,datos);
        Bien guardado = bienRepository.saveAndFlush(bien);
        movimientoBienService.registrar(guardado, antes, "EDICION", null, usuario, null);
        Map<String,Object> respuesta = convertirAMap(guardado);
        if (movimientoBienService.cambioPersona(antes, movimientoBienService.foto(guardado))) {
            Documento t = documentoFormatoService.registrarTransferencia(List.of(guardado), List.of(antes), null, usuario, usuario, null);
            if (t != null) respuesta.put("idDocumento", t.getIdDocumento());
        }
        return respuesta;
    }

    @Override
    public Map<String,Object> moverBienes(Map<String,Object> datos) {
        Usuario usuario = usuarioActual();
        Object idsCrudos = datos == null ? null : datos.get("ids");
        List<Long> ids = new ArrayList<>();
        if (idsCrudos instanceof Collection<?> c) {
            for (Object o : c) {
                try { ids.add(Long.valueOf(String.valueOf(o))); } catch (NumberFormatException ignored) { }
            }
        }
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se indicó ningún bien");
        if (ids.size() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Máximo 500 bienes por movimiento");
        boolean cambiaUbic = datos.containsKey("idUbicacion") && datos.get("idUbicacion") != null;
        boolean cambiaResp = datos.containsKey("idResponsableArea");
        boolean cambiaUsr = datos.containsKey("idUsuarioDirecto");
        if (!cambiaUbic && !cambiaResp && !cambiaUsr) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica la nueva ubicación o la persona");
        }
        String motivo = obtenerTexto(datos, "motivo");
        boolean conAutorizacion = !esAdmin(usuario) && requiereAutorizacion("MODIFICACION");
        int aplicados = 0, pendientes = 0;
        List<Bien> transferidos = new ArrayList<>();
        List<MovimientoBienService.Foto> fotosAntes = new ArrayList<>();
        for (Long id : ids) {
            Bien bien = buscarBienActivo(id);
            Long sedeActual = obtenerIdSede(bien);
            validarAccesoSede(usuario, sedeActual);
            Map<String,Object> nuevos = datosDesdeBien(bien);
            if (cambiaUbic) nuevos.put("idUbicacion", datos.get("idUbicacion"));
            if (cambiaResp) nuevos.put("idResponsableArea", datos.get("idResponsableArea"));
            if (cambiaUsr) nuevos.put("idUsuarioDirecto", datos.get("idUsuarioDirecto"));
            boolean conPersona = nuevos.get("idResponsableArea") != null || nuevos.get("idUsuarioDirecto") != null;
            nuevos.put("estatusAsignacion", conPersona ? "ASIGNADO" : "SIN_ASIGNAR");
            Long sedeNueva = obtenerIdSedeDesdeDatos(nuevos, sedeActual);
            validarAccesoSede(usuario, sedeNueva);
            if (conAutorizacion) {
                if (motivo != null) nuevos.put("motivoSolicitud", motivo);
                registrarSolicitud("MODIFICACION", bien, nuevos, usuario, sedeNueva);
                pendientes++;
                continue;
            }
            MovimientoBienService.Foto antes = movimientoBienService.foto(bien);
            asignarDatos(bien, nuevos);
            Bien guardado = bienRepository.saveAndFlush(bien);
            movimientoBienService.registrar(guardado, antes, "MOVIMIENTO", motivo, usuario, null);
            if (movimientoBienService.cambioPersona(antes, movimientoBienService.foto(guardado))) {
                transferidos.add(guardado);
                fotosAntes.add(antes);
            }
            aplicados++;
        }
        Map<String,Object> r = new LinkedHashMap<>();
        if (!transferidos.isEmpty()) {
            Documento t = documentoFormatoService.registrarTransferencia(transferidos, fotosAntes, motivo, usuario, usuario, null);
            if (t != null) r.put("idDocumento", t.getIdDocumento());
        }
        r.put("transferidos", transferidos.size());
        r.put("success", true);
        r.put("aplicados", aplicados);
        r.put("pendientes", pendientes);
        return r;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String,Object> siguienteClave(Long idUbicacion) {
        Sede sede = null;
        if (idUbicacion != null) {
            Ubicacion u = ubicacionRepository.findById(idUbicacion).orElse(null);
            if (u != null) sede = u.getSede();
        }
        Map<String,Object> r = new LinkedHashMap<>();
        String clave = claveBienService.siguienteClave(sede);
        boolean conPrefijo = sede != null && sede.getPrefijoClave() != null && !sede.getPrefijoClave().isBlank();
        String prefijo = conPrefijo ? sede.getPrefijoClave().trim().toUpperCase() : "B";
        r.put("clave", clave);
        r.put("conPrefijo", conPrefijo);
        r.put("prefijo", prefijo);
        r.put("numero", clave.length() > prefijo.length() ? clave.substring(prefijo.length()) : "");
        r.put("sede", sede != null ? sede.getNombreSede() : null);
        return r;
    }

    // ===================== Modo actualización (solo administrador, sin formatos) =====================
    // El administrador recorre las áreas cotejando: confirma lo que está, da de alta lo nuevo y de baja lo que falta.
    // Los movimientos quedan en el historial del bien, pero no se generan formatos de alta, baja ni transferencia.

    private static final Set<String> ESTADOS_FISICOS = Set.of("BUENO", "REGULAR", "MALO");

    private Usuario adminActual() {
        Usuario usuario = usuarioActual();
        if (!esAdmin(usuario)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el administrador puede actualizar el inventario");
        return usuario;
    }

    private Long ubicacionValida(Map<String,Object> datos) {
        Long idUbicacion;
        try { idUbicacion = obtenerLong(datos, "idUbicacion"); }
        catch (NumberFormatException e) { idUbicacion = null; }
        if (idUbicacion == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el área donde estás");
        if (!ubicacionRepository.existsById(idUbicacion)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El área no existe");
        return idUbicacion;
    }

    private String estadoFisicoValido(Map<String,Object> datos) {
        String e = obtenerTexto(datos, "estadoFisico");
        if (e == null || e.isBlank()) return null;
        String v = e.trim().toUpperCase();
        if (!ESTADOS_FISICOS.contains(v)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado físico no válido");
        return v;
    }

    private Long personaValida(Map<String,Object> datos, String campo) {
        Long id;
        try { id = obtenerLong(datos, campo); }
        catch (NumberFormatException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Persona no válida"); }
        if (id != null && !personaRepository.existsById(id)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La persona no existe");
        return id;
    }

    // Se marca solo: cuándo y quién vio el bien en su lugar
    private void marcarVerificado(Bien bien, Usuario usuario) {
        bien.setFechaVerificacion(java.time.LocalDateTime.now());
        String quien = usuario.getPersona() != null && usuario.getPersona().getNombreCompleto() != null
                ? usuario.getPersona().getNombreCompleto() : usuario.getCorreo();
        bien.setVerificadoPor(recortar(quien, 200));
    }

    private String recortar(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    @Override
    public Map<String,Object> actualizacionConfirmar(Map<String,Object> datos) {
        Usuario usuario = adminActual();
        if (datos == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Faltan datos");
        Long idBien;
        try { idBien = obtenerLong(datos, "idBien"); }
        catch (NumberFormatException e) { idBien = null; }
        if (idBien == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el bien");
        Bien bien = buscarBienActivo(idBien);

        Map<String,Object> nuevos = datosDesdeBien(bien);
        nuevos.put("idUbicacion", ubicacionValida(datos));
        String estado = estadoFisicoValido(datos);
        if (estado != null) nuevos.put("estadoFisico", estado);
        if (datos.containsKey("idUsuarioDirecto")) nuevos.put("idUsuarioDirecto", personaValida(datos, "idUsuarioDirecto"));
        boolean conPersona = nuevos.get("idResponsableArea") != null || nuevos.get("idUsuarioDirecto") != null;
        nuevos.put("estatusAsignacion", conPersona ? "ASIGNADO" : "SIN_ASIGNAR");

        MovimientoBienService.Foto antes = movimientoBienService.foto(bien);
        asignarDatos(bien, nuevos);
        marcarVerificado(bien, usuario);
        Bien guardado = bienRepository.saveAndFlush(bien);
        movimientoBienService.registrar(guardado, antes, "ACTUALIZACION", "Actualización del inventario", usuario, null);

        String etiqueta = obtenerTexto(datos, "etiqueta");
        if (etiqueta != null && !etiqueta.isBlank() && (guardado.getEtiqueta() == null || guardado.getEtiqueta().isBlank())) {
            etiquetaService.asignar(etiqueta, guardado.getIdBien(), false);
        }
        return convertirAMap(bienRepository.findById(guardado.getIdBien()).orElse(guardado));
    }

    @Override
    public Map<String,Object> actualizacionAlta(Map<String,Object> datos, byte[] foto, String tipoFoto) {
        Usuario usuario = adminActual();
        if (datos == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Faltan datos");
        String articulo = recortar(obtenerTexto(datos, "articulo"), 200);
        if (articulo == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe qué es el bien");
        if (foto != null && foto.length > 0) validarFoto(foto, tipoFoto);

        Map<String,Object> d = new HashMap<>();
        d.put("articulo", articulo);
        d.put("idUbicacion", ubicacionValida(datos));
        d.put("estadoFisico", estadoFisicoValido(datos));
        Long idPersona = personaValida(datos, "idUsuarioDirecto");
        d.put("idUsuarioDirecto", idPersona);
        d.put("estatusAsignacion", idPersona != null ? "ASIGNADO" : "SIN_ASIGNAR");
        d.put("modelo", recortar(obtenerTexto(datos, "modelo"), 150));
        d.put("numeroSerie", recortar(obtenerTexto(datos, "numeroSerie"), 150));
        d.put("caracteristicas", recortar(obtenerTexto(datos, "caracteristicas"), 500));
        try {
            d.put("idClasificacion", obtenerLong(datos, "idClasificacion"));
            d.put("idMarca", obtenerLong(datos, "idMarca"));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clasificación o marca no válida");
        }
        if (d.get("idClasificacion") != null && !clasificacionRepository.existsById((Long) d.get("idClasificacion"))) d.put("idClasificacion", null);
        if (d.get("idMarca") != null && !marcaRepository.existsById((Long) d.get("idMarca"))) d.put("idMarca", null);

        Bien bien = new Bien();
        asignarDatos(bien, d);
        bien.setUsuarioRegistro(usuario);
        marcarVerificado(bien, usuario);
        Bien guardado = bienRepository.saveAndFlush(bien);

        String etiqueta = obtenerTexto(datos, "etiqueta");
        if (etiqueta != null && !etiqueta.isBlank()) etiquetaService.asignar(etiqueta, guardado.getIdBien(), false);

        // La foto se sube al final: si algo de arriba falla, no queda una foto suelta
        if (foto != null && foto.length > 0) {
            String llave = fotoStorage.guardar("bienes/" + guardado.getIdBien() + "-" + UUID.randomUUID().toString().substring(0, 8), foto, tipoFoto);
            guardado.setFotoKey(llave);
            guardado.setFotoFecha(java.time.LocalDateTime.now());
            bienRepository.saveAndFlush(guardado);
        }
        movimientoBienService.registrarNota(guardado, "ALTA", "Alta durante la actualización del inventario", usuario);
        return convertirAMap(bienRepository.findById(guardado.getIdBien()).orElse(guardado));
    }

    @Override
    public Map<String,Object> actualizacionBaja(Map<String,Object> datos) {
        Usuario usuario = adminActual();
        if (datos == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Faltan datos");
        Long idBien;
        try { idBien = obtenerLong(datos, "idBien"); }
        catch (NumberFormatException e) { idBien = null; }
        if (idBien == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el bien");
        Bien bien = bienRepository.findById(idBien)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el bien"));
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("idBien", idBien);
        if (!Boolean.TRUE.equals(bien.getActivo())) return r;   // ya estaba de baja (se reintentó desde el celular)
        String motivo = recortar(obtenerTexto(datos, "motivo"), 500);
        bien.setActivo(false);
        bienRepository.saveAndFlush(bien);
        movimientoBienService.registrarNota(bien, "BAJA", "Baja durante la actualización del inventario" + (motivo != null ? ": " + motivo : ""), usuario);
        return r;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String,Object>> movimientos(Long idBien) {
        Bien bien = bienRepository.findById(idBien)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bien no encontrado"));
        validarAccesoSede(usuarioActual(), obtenerIdSede(bien));
        return movimientoBienService.listar(idBien);
    }

    @Override
    public Map<String,Object> eliminarBien(Long idBien) {
        return eliminarBien(idBien, null);
    }

    @Override
    public Map<String,Object> eliminarBien(Long idBien, String motivo) {
        String motivoBaja = motivo == null || motivo.isBlank() ? null
                : (motivo.trim().length() > 500 ? motivo.trim().substring(0, 500) : motivo.trim());
        Usuario usuario = usuarioActual();
        Bien bien = buscarBienActivo(idBien);
        Long idSede = obtenerIdSede(bien);
        validarAccesoSede(usuario, idSede);
        if (!esAdmin(usuario) && requiereAutorizacion("BAJA")) {
            Map<String,Object> datosBaja = new HashMap<>();
            if (motivoBaja != null) datosBaja.put("motivoSolicitud", motivoBaja);
            return registrarSolicitud("BAJA", bien, datosBaja, usuario, idSede);
        }
        bien.setActivo(false);
        bienRepository.save(bien);
        // Formato de baja registrado automáticamente
        Documento doc = documentoFormatoService.registrar("BAJA", bien, null, usuario, usuario,
                "Baja registrada directamente por el administrador." + (motivoBaja != null ? " Motivo: " + motivoBaja : ""));
        Map<String,Object> respuesta = new HashMap<>();
        respuesta.put("success",true);
        respuesta.put("message","Bien eliminado correctamente");
        respuesta.put("idBien",idBien);
        respuesta.put("idDocumento", doc.getIdDocumento());
        return respuesta;
    }

    @Override
    public Map<String,Object> importarBienes(List<Map<String,Object>> filas) {
        if (filas == null || filas.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El Excel no contiene registros");
        }

        List<Map<String,Object>> errores = new ArrayList<>();
        List<Bien> bienesGuardar = new ArrayList<>();
        Set<String> clavesExcel = new HashSet<>();
        int filaExcel = 1;

        List<Persona> personas = personaRepository.findByEstatusTrue();
        List<Ubicacion> ubicaciones = ubicacionRepository.findByEstatusTrue();

        for (Map<String,Object> fila : filas) {
            filaExcel++;

            try {
                Map<String,Object> datos = normalizarFila(fila);

                System.out.println("ENCABEZADOS RECIBIDOS: " + fila.keySet());
                System.out.println("DATOS NORMALIZADOS: " + datos);
                System.out.println("VALOR DE CLAVE: " + texto(datos,"CLAVE"));


                String clave = texto(datos,"CLAVE");
                String articuloCaracteristicas = texto(datos,"ARTICULOCARACTERISTICAS");

                if (clave.isBlank()) {
                    throw new IllegalArgumentException("La clave es obligatoria");
                }

                if (articuloCaracteristicas.isBlank()) {
                    throw new IllegalArgumentException("El artículo y sus características son obligatorios");
                }

                if (bienRepository.existsByClave(clave) || !clavesExcel.add(clave)) {
                    throw new IllegalArgumentException("La clave ya existe: " + clave);
                }

                String articulo = articuloCaracteristicas.trim();

                if (articulo.isBlank()) {
                    throw new IllegalArgumentException("El artículo está vacío");
                }

                Bien bien = new Bien();
                bien.setClave(clave);
                bien.setArticulo(articulo);
                bien.setCaracteristicas(null);
                bien.setNumeroSerie(texto(datos,"NOSERIE"));
                bien.setUnidadMedida(texto(datos,"UNIDADDEMEDIDA"));
                bien.setEstatusInventario(valorPorDefecto(texto(datos,"ESTATUS"),"ACTIVO").toUpperCase());
                bien.setEstadoFisico(texto(datos,"ESTADODELBIEN"));
                bien.setCantidadPiezas(1);
                bien.setEstatusAsignacion("SIN_ASIGNAR");
                bien.setActivo(true);

                String clasificacion = texto(datos,"CLASIFICACION");

                String marca = texto(datos,"MARCA");
                if (!marca.isBlank()) {
                    bien.setMarca(marcaRepository.findByNombreMarca(marca)
                            .orElseThrow(() -> new IllegalArgumentException("No existe la marca: " + marca)));
                }

                String color = texto(datos,"COLOR");
                if (!color.isBlank()) {
                    bien.setColor(colorRepository.findByNombreColor(color)
                            .orElseThrow(() -> new IllegalArgumentException("No existe el color: " + color)));
                }

                String entidad = texto(datos,"ENTIDAD");
                String sede = texto(datos,"SEDE");
                String responsable = texto(datos,"RESPONSABLE");
                String puestoDepartamento = texto(datos,"PUESTODEPARTAMENTO");

                Persona persona = null;

                if (!responsable.isBlank()) {
                    List<Persona> coincidencias = personas.stream()
                            .filter(p -> normalizar(p.getNombreCompleto()).equals(normalizar(responsable)))
                            .filter(p -> p.getSede() != null)
                            .filter(p -> entidad.isBlank() || normalizar(p.getSede().getEntidad()).equals(normalizar(entidad)))
                            .filter(p -> sede.isBlank() || normalizar(p.getSede().getNombreSede()).equals(normalizar(sede)))
                            .filter(p -> puestoDepartamento.isBlank() || coincidePuesto(p,puestoDepartamento))
                            .toList();

                    if (coincidencias.size() != 1) {
                        throw new IllegalArgumentException(coincidencias.isEmpty()
                                ? "No se encontró el responsable: " + responsable
                                : "El responsable tiene varias coincidencias: " + responsable);
                    }

                    persona = coincidencias.get(0);
                    bien.setResponsableArea(persona);
                }

                String nombreUbicacion = texto(datos,"UBICACION");

                if (!nombreUbicacion.isBlank()) {
                    Persona personaFinal = persona;

                    List<Ubicacion> coincidencias = ubicaciones.stream()
                            .filter(u -> normalizar(u.getNombreArea()).equals(normalizar(nombreUbicacion)))
                            .filter(u -> u.getSede() != null)
                            .filter(u -> entidad.isBlank() || normalizar(u.getSede().getEntidad()).equals(normalizar(entidad)))
                            .filter(u -> sede.isBlank() || normalizar(u.getSede().getNombreSede()).equals(normalizar(sede)))
                            .filter(u -> personaFinal == null || personaFinal.getSede() == null ||
                                    Objects.equals(u.getSede().getIdSede(),personaFinal.getSede().getIdSede()))
                            .toList();

                    if (coincidencias.size() != 1) {
                        throw new IllegalArgumentException(coincidencias.isEmpty()
                                ? "No se encontró la ubicación: " + nombreUbicacion
                                : "La ubicación tiene varias coincidencias: " + nombreUbicacion);
                    }

                    bien.setUbicacion(coincidencias.get(0));
                }

                bienesGuardar.add(bien);

            } catch (Exception e) {
                e.printStackTrace();
                Map<String,Object> error = new HashMap<>();
                error.put("fila",filaExcel);
                error.put("clave",fila != null ? fila.get("CLAVE") : null);
                error.put("error",e.getMessage() != null ? e.getMessage() : "Error al procesar el registro");
                errores.add(error);
            }
        }

        List<Bien> guardados = bienRepository.saveAll(bienesGuardar);

        Map<String,Object> respuesta = new HashMap<>();
        respuesta.put("success",errores.isEmpty());
        respuesta.put("message","Importación procesada");
        respuesta.put("total",filas.size());
        respuesta.put("importados",guardados.size());
        respuesta.put("rechazados",errores.size());
        respuesta.put("errores",errores);

        return respuesta;
    }

    private Usuario usuarioActual() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No hay un usuario autenticado");
        }
        return usuarioRepository.findByCorreoIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario autenticado no encontrado"));
    }

    private boolean esAdmin(Usuario usuario) {
        return usuario.getRoles() != null && usuario.getRoles().stream()
                .anyMatch(rol -> "ADMIN".equalsIgnoreCase(rol.getNombre()));
    }

    private Set<Long> idsSedes(Usuario usuario) {
        Set<Long> ids = new HashSet<>();
        if (usuario.getSedes() != null) {
            usuario.getSedes().forEach(sede -> ids.add(sede.getIdSede()));
        }
        return ids;
    }

    private Long obtenerIdSede(Bien bien) {
        return bien.getUbicacion() != null && bien.getUbicacion().getSede() != null
                ? bien.getUbicacion().getSede().getIdSede() : null;
    }

    private Long obtenerIdSedeDesdeDatos(Map<String,Object> datos, Long sedeActual) {
        Long idUbicacion = obtenerLong(datos, "idUbicacion");
        if (idUbicacion == null) return sedeActual;
        Ubicacion ubicacion = ubicacionRepository.findById(idUbicacion)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "La ubicación indicada no existe"));
        if (ubicacion.getSede() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La ubicación no tiene una sede asignada");
        }
        return ubicacion.getSede().getIdSede();
    }

    private void validarAccesoSede(Usuario usuario, Long idSede) {
        if (esAdmin(usuario)) return;
        if (idSede == null || !idsSedes(usuario).contains(idSede)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a la sede de este bien");
        }
    }

    private boolean requiereAutorizacion(String tipoOperacion) {
        return configuracionAutorizacionRepository.findByTipoOperacion(tipoOperacion)
                .map(c -> Boolean.TRUE.equals(c.getRequiereAutorizacion()))
                .orElse(true);
    }

    private Map<String,Object> registrarSolicitud(String tipo, Bien bien, Map<String,Object> datos,
                                                   Usuario usuario, Long idSede) {
        if (idSede == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo determinar la sede de la solicitud");
        }
        SolicitudBien solicitud = new SolicitudBien();
        solicitud.setTipoOperacion(tipo);
        solicitud.setBien(bien);
        solicitud.setDatosPropuestos(datos == null ? Collections.emptyMap() : new LinkedHashMap<>(datos));
        solicitud.setEstado("PENDIENTE");
        Object motivo = datos != null ? datos.get("motivoSolicitud") : null;
        if (motivo != null) solicitud.setMotivoSolicitud(motivo.toString());
        solicitud.setUsuarioSolicitante(usuario);
        solicitud.setIdSede(idSede);
        solicitudBienRepository.save(solicitud);
        // Aviso al celular de quienes pueden autorizarla
        notificacionService.nuevaSolicitud(solicitud);

        Map<String,Object> respuesta = new LinkedHashMap<>();
        respuesta.put("success", true);
        respuesta.put("pendienteAutorizacion", true);
        respuesta.put("idSolicitud", solicitud.getIdSolicitud());
        respuesta.put("message", "La solicitud fue enviada para autorización");
        return respuesta;
    }

    public Map<String,Object> aplicarSolicitudAprobada(SolicitudBien solicitud) {
        Map<String,Object> datos = solicitud.getDatosPropuestos() == null
                ? Collections.emptyMap() : solicitud.getDatosPropuestos();
        String operacion = solicitud.getTipoOperacion();
        if ("ALTA".equals(operacion)) {
            Bien nuevo = new Bien();
            asignarDatos(nuevo, datos);
            ponerFotoAlta(nuevo, datos);
            nuevo.setUsuarioRegistro(solicitud.getUsuarioSolicitante());
            Bien guardado = bienRepository.save(nuevo);
            // Se liga a la solicitud para poder revocar el alta
            solicitud.setBien(guardado);
            return convertirAMap(guardado);
        }
        Bien existente = solicitud.getBien();
        if (existente == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La solicitud no tiene un bien asociado");
        }
        if ("MODIFICACION".equals(operacion)) {
            // Copia de cómo estaba el bien, para poder revocar el cambio
            solicitud.setDatosAnteriores(datosDesdeBien(existente));
            MovimientoBienService.Foto antes = movimientoBienService.foto(existente);
            asignarDatos(existente, datos);
            Bien guardado = bienRepository.saveAndFlush(existente);
            String motivoSol = solicitud.getMotivoSolicitud();
            String quienPidio = solicitud.getUsuarioSolicitante() != null && solicitud.getUsuarioSolicitante().getPersona() != null
                    ? solicitud.getUsuarioSolicitante().getPersona().getNombreCompleto() : null;
            String nota = quienPidio != null ? "Solicitado por " + quienPidio + (motivoSol != null && !motivoSol.isBlank() ? ": " + motivoSol : "") : motivoSol;
            movimientoBienService.registrar(guardado, antes, "SOLICITUD", nota, usuarioActual(), solicitud.getIdSolicitud());
            if (movimientoBienService.cambioPersona(antes, movimientoBienService.foto(guardado))) {
                documentoFormatoService.registrarTransferencia(List.of(guardado), List.of(antes), motivoSol,
                        solicitud.getUsuarioSolicitante(), usuarioActual(), solicitud);
            }
            return convertirAMap(guardado);
        }
        if ("BAJA".equals(operacion)) {
            existente.setActivo(false);
            bienRepository.save(existente);
            return Map.of("success", true, "idBien", existente.getIdBien());
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de operación de solicitud no válido");
    }

    private boolean coincidePuesto(Persona persona, String valor) {

        if (persona.getPuesto() == null) {
            return false;
        }

        String buscado = normalizar(valor);

        String departamento = persona.getPuesto().getDepartamento() != null
                ? normalizar(persona.getPuesto().getDepartamento().getNombreDepartamento())
                : "";

        String puesto = normalizar(persona.getPuesto().getNombrePuesto());

        return buscado.equals(departamento)
                || buscado.equals(puesto)
                || buscado.equals(departamento + puesto)
                || buscado.equals(puesto + departamento);
    }

    private Map<String,Object> normalizarFila(Map<String,Object> fila) {
        Map<String,Object> resultado = new HashMap<>();
        if (fila == null) return resultado;
        fila.forEach((k,v) -> resultado.put(normalizar(k),v));
        return resultado;
    }

    private String texto(Map<String,Object> datos,String campo) {

        Object valor = datos.get(normalizar(campo));
        return valor == null ? "" : valor.toString().trim();
    }

    private String normalizar(String valor) {
        if (valor == null) return "";
        return Normalizer.normalize(valor.trim(),Normalizer.Form.NFD)
                .replaceAll("\\p{M}","")
                .replaceAll("[^a-zA-Z0-9]","")
                .toUpperCase();
    }

    private String valorPorDefecto(String valor,String defecto) {
        return valor == null || valor.isBlank() ? defecto : valor.trim();
    }

    private Bien buscarBienActivo(Long idBien) {
        return bienRepository.findByIdBienAndActivoTrue(idBien)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"No se encontró el bien solicitado"));
    }

    // Deshace en el inventario lo que aplicó una solicitud aprobada
    public void revertirSolicitudAprobada(SolicitudBien solicitud) {
        String operacion = solicitud.getTipoOperacion();

        if ("ALTA".equals(operacion)) {
            Bien creado = solicitud.getBien();
            if (creado == null) {
                // Altas aprobadas antes de ligar el bien: se localiza por su clave
                Map<String,Object> datos = solicitud.getDatosPropuestos() == null ? Collections.emptyMap() : solicitud.getDatosPropuestos();
                String clave = obtenerTexto(datos, "clave");
                creado = clave == null ? null : bienRepository.findByClave(clave).orElse(null);
            }
            if (creado == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "No se encontró el bien que creó esta solicitud");
            }
            final Long idCreado = creado.getIdBien();
            boolean tieneOtras = solicitudBienRepository.existsByBien_IdBienAndIdSolicitudNot(idCreado, solicitud.getIdSolicitud());
            if (tieneOtras) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "El bien creado ya tiene otras solicitudes (cambios o baja). Revócalas o resuélvelas primero.");
            }
            solicitud.setBien(null);
            solicitudBienRepository.saveAndFlush(solicitud);
            try {
                bienRepository.delete(creado);
                bienRepository.flush();
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "No se puede revocar el alta: el bien ya está relacionado con otros registros");
            }
            return;
        }

        Bien existente = solicitud.getBien();
        if (existente == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La solicitud no tiene un bien asociado");
        }
        if ("MODIFICACION".equals(operacion)) {
            if (solicitud.getDatosAnteriores() == null || solicitud.getDatosAnteriores().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Esta modificación se aprobó antes de habilitar la revocación y no hay copia de los datos anteriores");
            }
            if (!Boolean.TRUE.equals(existente.getActivo())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El bien ya fue dado de baja; no se puede revertir el cambio");
            }
            MovimientoBienService.Foto antes = movimientoBienService.foto(existente);
            asignarDatos(existente, solicitud.getDatosAnteriores());
            Bien revertido = bienRepository.saveAndFlush(existente);
            movimientoBienService.registrar(revertido, antes, "REVERSION", "Se revocó un cambio autorizado", usuarioActual(), solicitud.getIdSolicitud());
            solicitud.setDatosAnteriores(null);
            return;
        }
        if ("BAJA".equals(operacion)) {
            existente.setActivo(true);
            bienRepository.save(existente);
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de operación de solicitud no válido");
    }

    // Datos del bien con las mismas llaves que usa asignarDatos
    private Map<String,Object> datosDesdeBien(Bien b) {
        Map<String,Object> d = new HashMap<>();
        d.put("clave", b.getClave());
        d.put("articulo", b.getArticulo());
        d.put("caracteristicas", b.getCaracteristicas());
        d.put("modelo", b.getModelo());
        d.put("numeroSerie", b.getNumeroSerie());
        d.put("unidadMedida", b.getUnidadMedida());
        d.put("estatusInventario", b.getEstatusInventario());
        d.put("estatusAsignacion", b.getEstatusAsignacion());
        d.put("estadoFisico", b.getEstadoFisico());
        d.put("cantidadPiezas", b.getCantidadPiezas());
        d.put("idBienPadre", b.getBienPadre() != null ? b.getBienPadre().getIdBien() : null);
        d.put("idClasificacion", b.getClasificacion() != null ? b.getClasificacion().getIdClasificacion() : null);
        d.put("idSubcategoria", b.getSubcategoria() != null ? b.getSubcategoria().getIdSubcategoria() : null);
        d.put("idMarca", b.getMarca() != null ? b.getMarca().getIdMarca() : null);
        d.put("idColor", b.getColor() != null ? b.getColor().getIdColor() : null);
        d.put("idResponsableArea", b.getResponsableArea() != null ? b.getResponsableArea().getIdPersona() : null);
        d.put("idUsuarioDirecto", b.getUsuarioDirecto() != null ? b.getUsuarioDirecto().getIdPersona() : null);
        d.put("idUbicacion", b.getUbicacion() != null ? b.getUbicacion().getIdUbicacion() : null);
        d.put("requiereCalibracion", Boolean.TRUE.equals(b.getRequiereCalibracion()));
        d.put("fechaCalibracion", b.getFechaCalibracion() != null ? b.getFechaCalibracion().toString() : null);
        d.put("requiereMantenimiento", Boolean.TRUE.equals(b.getRequiereMantenimiento()));
        d.put("fechaMantenimiento", b.getFechaMantenimiento() != null ? b.getFechaMantenimiento().toString() : null);
        return d;
    }

    private void asignarDatos(Bien bien,Map<String,Object> datos) {
        bien.setClave(obtenerTexto(datos,"clave"));
        bien.setArticulo(obtenerTexto(datos,"articulo"));
        bien.setCaracteristicas(obtenerTexto(datos,"caracteristicas"));
        bien.setModelo(obtenerTexto(datos,"modelo"));
        bien.setNumeroSerie(obtenerTexto(datos,"numeroSerie"));
        bien.setUnidadMedida(obtenerTexto(datos,"unidadMedida"));
        bien.setEstatusInventario(valorPorDefecto(obtenerTexto(datos,"estatusInventario"),"ACTIVO"));
        bien.setEstatusAsignacion(valorPorDefecto(obtenerTexto(datos,"estatusAsignacion"),"SIN_ASIGNAR"));
        bien.setEstadoFisico(obtenerTexto(datos,"estadoFisico"));

        Integer cantidad = obtenerEntero(datos,"cantidadPiezas");
        bien.setCantidadPiezas(cantidad != null ? cantidad : 1);

        Long idBienPadre = obtenerLong(datos,"idBienPadre");
        bien.setBienPadre(idBienPadre != null ? bienRepository.getReferenceById(idBienPadre) : null);

        Long idClasificacion = obtenerLong(datos,"idClasificacion");
        bien.setClasificacion(idClasificacion != null ? clasificacionRepository.getReferenceById(idClasificacion) : null);

        Long idSubcategoria = obtenerLong(datos,"idSubcategoria");
        bien.setSubcategoria(idSubcategoria != null ? subcategoriaRepository.getReferenceById(idSubcategoria) : null);

        Long idMarca = obtenerLong(datos,"idMarca");
        bien.setMarca(idMarca != null ? marcaRepository.getReferenceById(idMarca) : null);

        Long idColor = obtenerLong(datos,"idColor");
        bien.setColor(idColor != null ? colorRepository.getReferenceById(idColor) : null);

        Long idResponsableArea = obtenerLong(datos,"idResponsableArea");
        bien.setResponsableArea(idResponsableArea != null ? personaRepository.getReferenceById(idResponsableArea) : null);

        Long idUsuarioDirecto = obtenerLong(datos,"idUsuarioDirecto");
        bien.setUsuarioDirecto(idUsuarioDirecto != null ? personaRepository.getReferenceById(idUsuarioDirecto) : null);

        Long idUbicacion = obtenerLong(datos,"idUbicacion");
        bien.setUbicacion(idUbicacion != null ? ubicacionRepository.getReferenceById(idUbicacion) : null);

        // Sin clave capturada: se genera sola con el prefijo de la sede
        if (bien.getClave() == null || bien.getClave().isBlank()) {
            bien.setClave(claveBienService.siguienteClave(bien.getUbicacion() != null ? bien.getUbicacion().getSede() : null));
        }

        // Calibración y mantenimiento: solo se tocan si vienen en los datos (solicitudes anteriores no los traen)
        if (datos.containsKey("requiereCalibracion")) bien.setRequiereCalibracion(Boolean.parseBoolean(String.valueOf(datos.get("requiereCalibracion"))));
        if (datos.containsKey("fechaCalibracion")) bien.setFechaCalibracion(fecha(datos.get("fechaCalibracion")));
        if (datos.containsKey("requiereMantenimiento")) bien.setRequiereMantenimiento(Boolean.parseBoolean(String.valueOf(datos.get("requiereMantenimiento"))));
        if (datos.containsKey("fechaMantenimiento")) bien.setFechaMantenimiento(fecha(datos.get("fechaMantenimiento")));
        if (!Boolean.TRUE.equals(bien.getRequiereCalibracion())) bien.setFechaCalibracion(null);
        if (!Boolean.TRUE.equals(bien.getRequiereMantenimiento())) bien.setFechaMantenimiento(null);
    }

    private static java.time.LocalDate fecha(Object v) {
        if (v == null || String.valueOf(v).isBlank() || "null".equals(String.valueOf(v))) return null;
        try { return java.time.LocalDate.parse(String.valueOf(v).trim().substring(0, 10)); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fecha no válida: " + v); }
    }

    private Map<String,Object> convertirAMap(Bien bien) {
        Map<String,Object> resultado = new HashMap<>();
        resultado.put("id_bien",bien.getIdBien());
        resultado.put("clave",bien.getClave());
        resultado.put("codigo_qr",bien.getCodigoQr());
        resultado.put("etiqueta",bien.getEtiqueta());
        resultado.put("tiene_foto",bien.getFotoKey() != null);
        resultado.put("foto_mini",fotoStorage.urlMini(bien.getFotoKey()));
        resultado.put("foto_url",fotoStorage.urlGrande(bien.getFotoKey()));
        resultado.put("id_bien_padre",bien.getBienPadre() != null ? bien.getBienPadre().getIdBien() : null);
        resultado.put("id_clasificacion",bien.getClasificacion() != null ? bien.getClasificacion().getIdClasificacion() : null);
        resultado.put("clasificacion",bien.getClasificacion() != null ? bien.getClasificacion().getNombre() : null);
        resultado.put("id_subcategoria",bien.getSubcategoria() != null ? bien.getSubcategoria().getIdSubcategoria() : null);
        resultado.put("subcategoria",bien.getSubcategoria() != null ? bien.getSubcategoria().getNombreSubcategoria() : null);
        resultado.put("articulo",bien.getArticulo());
        resultado.put("caracteristicas",bien.getCaracteristicas());
        resultado.put("id_marca",bien.getMarca() != null ? bien.getMarca().getIdMarca() : null);
        resultado.put("marca",bien.getMarca() != null ? bien.getMarca().getNombreMarca() : null);
        resultado.put("modelo",bien.getModelo());
        resultado.put("numero_serie",bien.getNumeroSerie());
        resultado.put("id_color",bien.getColor() != null ? bien.getColor().getIdColor() : null);
        resultado.put("color",bien.getColor() != null ? bien.getColor().getNombreColor() : null);
        resultado.put("unidad_medida",bien.getUnidadMedida());
        resultado.put("cantidad_piezas",bien.getCantidadPiezas());
        resultado.put("id_responsable_area",bien.getResponsableArea() != null ? bien.getResponsableArea().getIdPersona() : null);
        resultado.put("responsable",bien.getResponsableArea() != null ? bien.getResponsableArea().getNombreCompleto() : null);
        resultado.put("id_usuario_directo",bien.getUsuarioDirecto() != null ? bien.getUsuarioDirecto().getIdPersona() : null);
        resultado.put("usuario_directo",bien.getUsuarioDirecto() != null ? bien.getUsuarioDirecto().getNombreCompleto() : null);
        Sede sedeBien = bien.getUbicacion() != null ? bien.getUbicacion().getSede() : null;
        resultado.put("id_sede",sedeBien != null ? sedeBien.getIdSede() : null);
        resultado.put("sede",sedeBien != null ? sedeBien.getNombreSede() : null);
        resultado.put("id_ubicacion",bien.getUbicacion() != null ? bien.getUbicacion().getIdUbicacion() : null);
        resultado.put("ubicacion",bien.getUbicacion() != null ? bien.getUbicacion().getNombreArea() : null);
        resultado.put("estatus_inventario",bien.getEstatusInventario());
        resultado.put("estatus_asignacion",bien.getEstatusAsignacion());
        resultado.put("estado_fisico",bien.getEstadoFisico());
        resultado.put("fecha_alta",bien.getFechaAlta());
        resultado.put("activo",bien.getActivo());
        resultado.put("requiere_calibracion",Boolean.TRUE.equals(bien.getRequiereCalibracion()));
        resultado.put("fecha_calibracion",bien.getFechaCalibracion());
        resultado.put("requiere_mantenimiento",Boolean.TRUE.equals(bien.getRequiereMantenimiento()));
        resultado.put("fecha_mantenimiento",bien.getFechaMantenimiento());
        resultado.put("fecha_actualizacion",bien.getFechaActualizacion());
        resultado.put("fecha_verificacion",bien.getFechaVerificacion());
        resultado.put("verificado_por",bien.getVerificadoPor());
        resultado.put("id_usuario_registro",bien.getUsuarioRegistro() != null ? bien.getUsuarioRegistro().getIdUsuario() : null);
        return resultado;
    }

    private String obtenerTexto(Map<String,Object> datos,String campo) {
        Object valor = datos.get(campo);
        return valor == null ? null : valor.toString();
    }

    private Long obtenerLong(Map<String,Object> datos,String campo) {
        Object valor = datos.get(campo);
        return valor == null || valor.toString().isBlank() ? null : Long.valueOf(valor.toString());
    }

    private Integer obtenerEntero(Map<String,Object> datos,String campo) {
        Object valor = datos.get(campo);
        return valor == null || valor.toString().isBlank() ? null : Integer.valueOf(valor.toString());
    }
}

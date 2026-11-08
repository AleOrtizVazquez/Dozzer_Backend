
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
        Bien bien = new Bien();
        asignarDatos(bien,datos);
        bien.setUsuarioRegistro(usuario);
        return convertirAMap(bienRepository.save(bien));
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
        asignarDatos(bien,datos);
        return convertirAMap(bienRepository.save(bien));
    }

    @Override
    public Map<String,Object> eliminarBien(Long idBien) {
        Usuario usuario = usuarioActual();
        Bien bien = buscarBienActivo(idBien);
        Long idSede = obtenerIdSede(bien);
        validarAccesoSede(usuario, idSede);
        if (!esAdmin(usuario) && requiereAutorizacion("BAJA")) {
            return registrarSolicitud("BAJA", bien, Collections.emptyMap(), usuario, idSede);
        }
        bien.setActivo(false);
        bienRepository.save(bien);
        Map<String,Object> respuesta = new HashMap<>();
        respuesta.put("success",true);
        respuesta.put("message","Bien eliminado correctamente");
        respuesta.put("idBien",idBien);
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
            nuevo.setUsuarioRegistro(solicitud.getUsuarioSolicitante());
            return convertirAMap(bienRepository.save(nuevo));
        }
        Bien existente = solicitud.getBien();
        if (existente == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La solicitud no tiene un bien asociado");
        }
        if ("MODIFICACION".equals(operacion)) {
            asignarDatos(existente, datos);
            return convertirAMap(bienRepository.save(existente));
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
    }

    private Map<String,Object> convertirAMap(Bien bien) {
        Map<String,Object> resultado = new HashMap<>();
        resultado.put("id_bien",bien.getIdBien());
        resultado.put("clave",bien.getClave());
        resultado.put("codigo_qr",bien.getCodigoQr());
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
        resultado.put("fecha_actualizacion",bien.getFechaActualizacion());
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

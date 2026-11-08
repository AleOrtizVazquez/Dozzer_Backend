
package com.dozzer.controller;

import com.dozzer.entity.SolicitudBien;
import com.dozzer.entity.Usuario;
import com.dozzer.repository.SedeRepository;
import com.dozzer.repository.UsuarioRepository;
import com.dozzer.service.SolicitudBienService;
import com.dozzer.service.impl.SolicitudBienWorkflowService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/solicitudes-bienes")
public class SolicitudBienController {

    @Autowired
    private SolicitudBienService solicitudBienService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private SolicitudBienWorkflowService workflowService;

    @Autowired
    private SedeRepository sedeRepository;

    // Obtener usuario autenticado
    private Usuario obtenerUsuarioActual(Authentication authentication) {

        return usuarioRepository.findByCorreoIgnoreCase(
                authentication.getName()
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Usuario no encontrado"
        ));
    }

    // Convertir solicitud a respuesta sin exponer entidades relacionadas
    private Map<String, Object> convertirAMap(SolicitudBien solicitud) {

        Map<String, Object> respuesta = new LinkedHashMap<>();

        respuesta.put("idSolicitud", solicitud.getIdSolicitud());
        respuesta.put("tipoOperacion", solicitud.getTipoOperacion());
        respuesta.put("idBien",
                solicitud.getBien() != null
                        ? solicitud.getBien().getIdBien()
                        : null);
        if (solicitud.getBien() != null) {
            respuesta.put("claveBien", solicitud.getBien().getClave());
            respuesta.put("articuloBien", solicitud.getBien().getArticulo());
        }
        Usuario solicitante = solicitud.getUsuarioSolicitante();
        respuesta.put("correoSolicitante", solicitante.getCorreo());
        respuesta.put("nombreSolicitante", nombreUsuario(solicitante));
        if (solicitud.getUsuarioRevisor() != null) {
            respuesta.put("correoRevisor", solicitud.getUsuarioRevisor().getCorreo());
            respuesta.put("nombreRevisor", nombreUsuario(solicitud.getUsuarioRevisor()));
        }
        sedeRepository.findById(solicitud.getIdSede())
                .ifPresent(sede -> respuesta.put("nombreSede", sede.getNombreSede()));
        respuesta.put("datosPropuestos", solicitud.getDatosPropuestos());
        respuesta.put("estado", solicitud.getEstado());
        respuesta.put("motivoSolicitud", solicitud.getMotivoSolicitud());
        respuesta.put("motivoResolucion", solicitud.getMotivoResolucion());
        respuesta.put("idUsuarioSolicitante",
                solicitud.getUsuarioSolicitante().getIdUsuario());
        respuesta.put("idUsuarioRevisor",
                solicitud.getUsuarioRevisor() != null
                        ? solicitud.getUsuarioRevisor().getIdUsuario()
                        : null);
        respuesta.put("fechaSolicitud", solicitud.getFechaSolicitud());
        respuesta.put("fechaResolucion", solicitud.getFechaResolucion());
        respuesta.put("idSede", solicitud.getIdSede());
        respuesta.put("motivoRevocacion", solicitud.getMotivoRevocacion());
        respuesta.put("fechaRevocacion", solicitud.getFechaRevocacion());
        if (solicitud.getIdUsuarioRevoco() != null) {
            usuarioRepository.findById(solicitud.getIdUsuarioRevoco())
                    .ifPresent(u -> respuesta.put("nombreRevoco", nombreUsuario(u)));
        }
        String estado = solicitud.getEstado() == null ? "" : solicitud.getEstado().toUpperCase();
        respuesta.put("revocable", "RECHAZADA".equals(estado)
                || ("APROBADA".equals(estado)
                    && (!"MODIFICACION".equals(solicitud.getTipoOperacion()) || solicitud.getDatosAnteriores() != null)));

        return respuesta;
    }

    private String nombreUsuario(Usuario usuario) {
        return usuario.getPersona() != null && usuario.getPersona().getNombreCompleto() != null
                ? usuario.getPersona().getNombreCompleto()
                : usuario.getCorreo();
    }

    private List<Map<String, Object>> convertirLista(
            List<SolicitudBien> solicitudes) {

        return solicitudes.stream()
                .map(this::convertirAMap)
                .toList();
    }

    // EMPLEADO: consultar sus propias solicitudes
    @Transactional(readOnly = true)
    @GetMapping("/mis-solicitudes")
    public List<Map<String, Object>> misSolicitudes(
            Authentication authentication) {

        Usuario usuario = obtenerUsuarioActual(authentication);

        return convertirLista(
                solicitudBienService.listarPorUsuario(
                        usuario.getIdUsuario()
                )
        );
    }

    // ADMINISTRADOR: consultar todas las solicitudes
    @Transactional(readOnly = true)
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listarTodas() {

        return convertirLista(
                solicitudBienService.listarTodas()
        );
    }

    // ADMINISTRADOR: consultar solicitudes por estado
    @Transactional(readOnly = true)
    @GetMapping("/estado/{estado}")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listarPorEstado(
            @PathVariable String estado) {

        return convertirLista(
                solicitudBienService.listarPorEstado(
                        estado.toUpperCase()
                )
        );
    }

    // ADMINISTRADOR o revisor autorizado: consultar solicitudes pendientes de sus sedes
    @Transactional(readOnly = true)
    @GetMapping("/pendientes")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SOLICITUDES_AUTORIZAR')")
    public List<Map<String, Object>> listarPendientes(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (admin) {
            return convertirLista(solicitudBienService.listarPorEstado("PENDIENTE"));
        }
        Usuario usuario = obtenerUsuarioActual(authentication);
        List<SolicitudBien> pendientes = new ArrayList<>();
        usuario.getSedes().forEach(sede -> pendientes.addAll(
                solicitudBienService.listarPendientesPorSede(sede.getIdSede())));
        return convertirLista(pendientes.stream()
                .filter(s -> !s.getUsuarioSolicitante().getIdUsuario().equals(usuario.getIdUsuario()))
                .toList());
    }

    // ADMINISTRADOR: consultar solicitudes de una sede
    @Transactional(readOnly = true)
    @GetMapping("/sede/{idSede}")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listarPorSede(
            @PathVariable Long idSede) {

        return convertirLista(
                solicitudBienService.listarPorSede(idSede)
        );
    }

    // Aprobar solicitud y aplicar el cambio al inventario
    @PostMapping("/{idSolicitud}/aprobar")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SOLICITUDES_AUTORIZAR')")
    public Map<String,Object> aprobar(@PathVariable Long idSolicitud,
                                      @RequestBody(required = false) Map<String,Object> datos) {
        String motivo = datos != null && datos.get("motivoResolucion") != null
                ? datos.get("motivoResolucion").toString() : null;
        return workflowService.resolver(idSolicitud, true, motivo);
    }

    // Rechazar solicitud indicando el motivo
    @PostMapping("/{idSolicitud}/rechazar")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('SOLICITUDES_AUTORIZAR')")
    public Map<String,Object> rechazar(@PathVariable Long idSolicitud,
                                       @RequestBody Map<String,Object> datos) {
        String motivo = datos != null && datos.get("motivoResolucion") != null
                ? datos.get("motivoResolucion").toString() : null;
        return workflowService.resolver(idSolicitud, false, motivo);
    }

    // Revocar una autorización (solo ADMIN): deshace el cambio y la regresa a pendientes
    @PostMapping("/{idSolicitud}/revocar")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String,Object> revocar(@PathVariable Long idSolicitud,
                                      @RequestBody Map<String,Object> datos) {
        String motivo = datos != null && datos.get("motivo") != null ? datos.get("motivo").toString() : null;
        return workflowService.revocar(idSolicitud, motivo);
    }

    // Consultar una solicitud: administrador o propietario
    @Transactional(readOnly = true)
    @GetMapping("/{idSolicitud}")
    public Map<String, Object> buscarPorId(
            @PathVariable Long idSolicitud,
            Authentication authentication) {

        SolicitudBien solicitud =
                solicitudBienService.buscarPorId(idSolicitud);

        if (solicitud == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Solicitud no encontrada"
            );
        }

        Usuario usuario = obtenerUsuarioActual(authentication);

        boolean esAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        boolean esPropietario = solicitud.getUsuarioSolicitante()
                .getIdUsuario()
                .equals(usuario.getIdUsuario());

        if (!esAdmin && !esPropietario) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "No tienes permiso para consultar esta solicitud"
            );
        }

        return convertirAMap(solicitud);
    }
}

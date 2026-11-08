package com.dozzer.service.impl;

import com.dozzer.entity.SolicitudBien;
import com.dozzer.entity.Usuario;
import com.dozzer.repository.SolicitudBienRepository;
import com.dozzer.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@Transactional
public class SolicitudBienWorkflowService {

    @Autowired private SolicitudBienRepository solicitudRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private BienServiceImpl bienService;

    public Map<String,Object> resolver(Long idSolicitud, boolean aprobar, String motivo) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        Usuario revisor = usuarioRepository.findByCorreoIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
        boolean admin = revisor.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getNombre()));
        boolean tienePermiso = auth.getAuthorities().stream()
                .anyMatch(a -> "SOLICITUDES_AUTORIZAR".equals(a.getAuthority()));
        if (!admin && !tienePermiso) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes permiso para resolver solicitudes");
        }

        SolicitudBien solicitud = solicitudRepository.findById(idSolicitud)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitud no encontrada"));
        if (!"PENDIENTE".equalsIgnoreCase(solicitud.getEstado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La solicitud ya fue resuelta");
        }
        if (!admin) {
            boolean sedeAsignada = revisor.getSedes().stream()
                    .anyMatch(s -> s.getIdSede().equals(solicitud.getIdSede()));
            if (!sedeAsignada) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a la sede de esta solicitud");
            if (solicitud.getUsuarioSolicitante().getIdUsuario().equals(revisor.getIdUsuario())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes autorizar tu propia solicitud");
            }
        }
        if (!aprobar && (motivo == null || motivo.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el motivo del rechazo");
        }
        if (motivo != null && motivo.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El motivo no puede superar 500 caracteres");
        }
        if (solicitud.getBien() != null && !Boolean.TRUE.equals(solicitud.getBien().getActivo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El bien asociado ya no está activo");
        }

        if (aprobar) {
            bienService.aplicarSolicitudAprobada(solicitud);
            solicitud.setEstado("APROBADA");
        } else {
            solicitud.setEstado("RECHAZADA");
        }
        solicitud.setMotivoResolucion(motivo == null || motivo.isBlank() ? null : motivo.trim());
        solicitud.setUsuarioRevisor(revisor);
        solicitud.setFechaResolucion(LocalDateTime.now());
        solicitudRepository.save(solicitud);

        return Map.of("success", true, "idSolicitud", idSolicitud,
                "estado", solicitud.getEstado(),
                "message", aprobar ? "Solicitud aprobada y aplicada al inventario" : "Solicitud rechazada");
    }
}

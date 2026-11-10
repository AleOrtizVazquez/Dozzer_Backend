package com.dozzer.service.impl;

import com.dozzer.entity.Bien;
import com.dozzer.entity.SolicitudBien;
import com.dozzer.entity.Usuario;
import com.dozzer.repository.SedeRepository;
import com.dozzer.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

// Qué avisos se mandan al celular y a quién. Nunca interrumpe la operación si algo falla.
@Service
public class NotificacionService {

    private static final Logger log = LoggerFactory.getLogger(NotificacionService.class);

    @Autowired private PushService push;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private SedeRepository sedeRepository;

    // Nueva solicitud: a los administradores (generales o de esa sede) y revisores de la sede
    public void nuevaSolicitud(SolicitudBien s) {
        try {
            Long idSede = s.getIdSede();
            Long solicitante = s.getUsuarioSolicitante() != null ? s.getUsuarioSolicitante().getIdUsuario() : null;
            Set<Long> destino = usuarioRepository.findByActivoTrue().stream()
                    .filter(u -> !Objects.equals(u.getIdUsuario(), solicitante))
                    .filter(u -> puedeAutorizar(u, idSede))
                    .map(Usuario::getIdUsuario)
                    .collect(Collectors.toSet());
            String sede = idSede == null ? "" : sedeRepository.findById(idSede).map(x -> " · " + x.getNombreSede()).orElse("");
            String cuerpo = nombre(s.getUsuarioSolicitante()) + " solicita " + operacion(s.getTipoOperacion()) + " de " + bien(s) + sede;
            push.enviarDespues(destino, "Nueva autorización pendiente", cuerpo, "/?ir=autorizaciones", "solicitud-" + s.getIdSolicitud());
        } catch (Exception e) {
            log.warn("No se pudo preparar el aviso de nueva solicitud: {}", e.getMessage());
        }
    }

    // Solicitud aprobada o rechazada: al empleado que la hizo
    public void solicitudResuelta(SolicitudBien s) {
        try {
            if (s.getUsuarioSolicitante() == null) return;
            boolean aprobada = "APROBADA".equalsIgnoreCase(s.getEstado());
            String titulo = aprobada ? "Tu solicitud fue aprobada ✅" : "Tu solicitud fue rechazada";
            String cuerpo = capitalizar(operacion(s.getTipoOperacion())) + " de " + bien(s)
                    + (!aprobada && s.getMotivoResolucion() != null ? " · Motivo: " + s.getMotivoResolucion() : "");
            push.enviarDespues(Set.of(s.getUsuarioSolicitante().getIdUsuario()), titulo, cuerpo, "/?ir=solicitudes",
                    "resuelta-" + s.getIdSolicitud());
        } catch (Exception e) {
            log.warn("No se pudo preparar el aviso de solicitud resuelta: {}", e.getMessage());
        }
    }

    private static boolean puedeAutorizar(Usuario u, Long idSede) {
        boolean admin = u.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getNombre()));
        boolean deLaSede = idSede != null && u.getSedes().stream().anyMatch(x -> idSede.equals(x.getIdSede()));
        if (admin) return u.getSedes().isEmpty() || deLaSede;
        boolean revisor = u.getRoles().stream().flatMap(r -> r.getPermisos().stream())
                .anyMatch(p -> "SOLICITUDES_AUTORIZAR".equals(p.getCodigo()));
        return revisor && deLaSede;
    }

    private static String operacion(String tipo) {
        if ("ALTA".equals(tipo)) return "alta";
        if ("BAJA".equals(tipo)) return "baja";
        return "modificación";
    }

    private static String bien(SolicitudBien s) {
        Bien b = s.getBien();
        if (b != null) return (b.getArticulo() != null ? b.getArticulo() : "un bien") + (b.getClave() != null ? " (" + b.getClave() + ")" : "");
        Map<String, Object> d = s.getDatosPropuestos();
        Object articulo = d == null ? null : d.get("articulo");
        return articulo != null && !String.valueOf(articulo).isBlank() ? String.valueOf(articulo) : "un bien";
    }

    private static String nombre(Usuario u) {
        if (u == null) return "Alguien";
        return u.getPersona() != null && u.getPersona().getNombreCompleto() != null ? u.getPersona().getNombreCompleto() : u.getCorreo();
    }

    private static String capitalizar(String t) {
        return t == null || t.isEmpty() ? "" : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}

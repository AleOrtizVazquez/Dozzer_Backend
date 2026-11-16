package com.dozzer.service.impl;

import com.dozzer.entity.Bien;
import com.dozzer.entity.Persona;
import com.dozzer.entity.Usuario;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Historial de movimientos de un bien: dónde estaba, con quién y quién lo movió
@Service
@Transactional
public class MovimientoBienService {

    @Autowired private JdbcTemplate jdbc;

    // Cómo estaba el bien en un momento (solo lo que cuenta como "movimiento")
    public record Foto(String sede, String ubicacion, String responsable, String usuario) {}

    public Foto foto(Bien b) {
        String sede = null, ubicacion = null;
        if (b.getUbicacion() != null) {
            ubicacion = b.getUbicacion().getNombreArea();
            if (b.getUbicacion().getSede() != null) sede = b.getUbicacion().getSede().getNombreSede();
        }
        return new Foto(sede, ubicacion, nombre(b.getResponsableArea()), nombre(b.getUsuarioDirecto()));
    }

    // Guarda el movimiento solo si cambió la ubicación, el responsable o el usuario directo
    public void registrar(Bien bien, Foto antes, String origen, String motivo, Usuario quien, Long idSolicitud) {
        if (bien == null || bien.getIdBien() == null || antes == null) return;
        Foto despues = foto(bien);
        if (Objects.equals(antes, despues)) return;
        boolean cambioUbic = !Objects.equals(antes.ubicacion(), despues.ubicacion()) || !Objects.equals(antes.sede(), despues.sede());
        boolean cambioResp = !Objects.equals(antes.responsable(), despues.responsable());
        boolean cambioUsr = !Objects.equals(antes.usuario(), despues.usuario());
        String porQuien = null;
        if (quien != null) {
            porQuien = quien.getPersona() != null && quien.getPersona().getNombreCompleto() != null
                    ? quien.getPersona().getNombreCompleto() : quien.getCorreo();
        }
        jdbc.update("insert into bien_movimientos (id_bien, fecha, origen, sede_anterior, sede_nueva, ubicacion_anterior, ubicacion_nueva, " +
                        "responsable_anterior, responsable_nuevo, usuario_anterior, usuario_nuevo, motivo, id_usuario_registro, registrado_por, id_solicitud) " +
                        "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bien.getIdBien(), Timestamp.valueOf(LocalDateTime.now()), origen,
                cambioUbic ? antes.sede() : null, cambioUbic ? despues.sede() : null,
                cambioUbic ? antes.ubicacion() : null, cambioUbic ? despues.ubicacion() : null,
                cambioResp ? antes.responsable() : null, cambioResp ? despues.responsable() : null,
                cambioUsr ? antes.usuario() : null, cambioUsr ? despues.usuario() : null,
                recortar(motivo, 500), quien != null ? quien.getIdUsuario() : null, recortar(porQuien, 200), idSolicitud);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar(Long idBien) {
        return jdbc.queryForList("select id_movimiento, fecha, origen, sede_anterior, sede_nueva, ubicacion_anterior, ubicacion_nueva, " +
                "responsable_anterior, responsable_nuevo, usuario_anterior, usuario_nuevo, motivo, registrado_por " +
                "from bien_movimientos where id_bien = ? order by fecha desc, id_movimiento desc limit 100", idBien);
    }

    private String nombre(Persona p) {
        return p == null ? null : p.getNombreCompleto();
    }

    private String recortar(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}

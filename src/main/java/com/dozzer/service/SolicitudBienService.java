package com.dozzer.service;


import com.dozzer.entity.SolicitudBien;
import java.util.List;

public interface SolicitudBienService {

    List<SolicitudBien> listarTodas();

    List<SolicitudBien> listarPorEstado(String estado);

    List<SolicitudBien> listarPorUsuario(Long idUsuario);

    List<SolicitudBien> listarPorEstadoYUsuario(
            String estado, Long idUsuario);

    List<SolicitudBien> listarPorSede(Long idSede);

    List<SolicitudBien> listarPendientesPorSede(Long idSede);

    SolicitudBien buscarPorId(Long idSolicitud);

    SolicitudBien guardar(SolicitudBien solicitud);

    void eliminar(Long idSolicitud);
}

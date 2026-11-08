package com.dozzer.service.impl;

import com.dozzer.entity.SolicitudBien;
import com.dozzer.repository.SolicitudBienRepository;
import com.dozzer.service.SolicitudBienService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SolicitudBienServiceImpl implements SolicitudBienService {

    @Autowired
    private SolicitudBienRepository solicitudBienRepository;

    @Override
    public List<SolicitudBien> listarTodas() {
        return solicitudBienRepository.findAll();
    }

    @Override
    public List<SolicitudBien> listarPorEstado(String estado) {
        return solicitudBienRepository
                .findByEstadoOrderByFechaSolicitudDesc(estado);
    }

    @Override
    public List<SolicitudBien> listarPorUsuario(Long idUsuario) {
        return solicitudBienRepository
                .findByUsuarioSolicitanteIdUsuarioOrderByFechaSolicitudDesc(idUsuario);
    }

    @Override
    public List<SolicitudBien> listarPorEstadoYUsuario(
            String estado, Long idUsuario) {
        return solicitudBienRepository
                .findByEstadoAndUsuarioSolicitanteIdUsuarioOrderByFechaSolicitudDesc(
                        estado, idUsuario);
    }

    @Override
    public List<SolicitudBien> listarPorSede(Long idSede) {
        return solicitudBienRepository
                .findByIdSedeOrderByFechaSolicitudDesc(idSede);
    }

    @Override
    public List<SolicitudBien> listarPendientesPorSede(Long idSede) {
        return solicitudBienRepository
                .findByEstadoAndIdSedeOrderByFechaSolicitudDesc(
                        "PENDIENTE", idSede);
    }

    @Override
    public SolicitudBien buscarPorId(Long idSolicitud) {
        return solicitudBienRepository.findById(idSolicitud)
                .orElse(null);
    }

    @Override
    public SolicitudBien guardar(SolicitudBien solicitud) {
        return solicitudBienRepository.save(solicitud);
    }

    @Override
    public void eliminar(Long idSolicitud) {
        solicitudBienRepository.deleteById(idSolicitud);
    }
}


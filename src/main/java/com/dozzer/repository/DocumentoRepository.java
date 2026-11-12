package com.dozzer.repository;

import com.dozzer.entity.Documento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface DocumentoRepository extends JpaRepository<Documento, Long> {

    List<Documento> findAllByOrderByIdDocumentoDesc();

    List<Documento> findByIdSedeInOrderByIdDocumentoDesc(Collection<Long> idsSedes);

    List<Documento> findByIdUsuarioSolicitanteOrderByIdDocumentoDesc(Long idUsuario);

    List<Documento> findByIdSolicitud(Long idSolicitud);

    // Empleado: lo que solicitó y sus resguardos
    List<Documento> findByIdUsuarioSolicitanteOrIdPersonaOrderByIdDocumentoDesc(Long idUsuario, Long idPersona);

    List<Documento> findByIdLevantamiento(Long idLevantamiento);

    java.util.Optional<Documento> findFirstByIdLevantamientoAndTipoAndIdPersona(Long idLevantamiento, String tipo, Long idPersona);

    List<Documento> findByTipoAndIdPersonaAndEstado(String tipo, Long idPersona, String estado);

    List<Documento> findByTipoOrderByIdDocumentoDesc(String tipo);

    long countByTipo(String tipo);

    boolean existsByFolio(String folio);
}

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
}

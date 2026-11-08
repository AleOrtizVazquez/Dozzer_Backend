package com.dozzer.repository;

import com.dozzer.entity.ConfiguracionAutorizacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConfiguracionAutorizacionRepository extends JpaRepository<ConfiguracionAutorizacion, Long> {

    Optional<ConfiguracionAutorizacion> findByTipoOperacion(String tipoOperacion);

}

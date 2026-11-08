
package com.dozzer.service;

import com.dozzer.entity.Usuario;
import com.dozzer.entity.Sede;
import com.dozzer.repository.UsuarioRepository;
import com.dozzer.repository.SedeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class UsuarioSedeService {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private SedeRepository sedeRepository;

    @Transactional
    public Usuario asignarSedes(Long idUsuario, List<Long> idsSedes) {

        Usuario usuario = usuarioRepository.findById(idUsuario)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        List<Sede> sedes = sedeRepository.findAllById(idsSedes);

        if (sedes.size() != idsSedes.stream().distinct().count()) {
            throw new RuntimeException("Una o más sedes no existen");
        }

        usuario.getSedes().clear();
        usuario.getSedes().addAll(sedes);

        return usuarioRepository.save(usuario);
    }

    @Transactional(readOnly = true)
    public List<Sede> consultarSedes(Long idUsuario) {

        Usuario usuario = usuarioRepository.findById(idUsuario)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        return List.copyOf(usuario.getSedes());
    }


    @Transactional(readOnly = true)
    public boolean esPropietario(String correo, Long idUsuario) {

        return usuarioRepository.findByCorreoIgnoreCase(correo)
                .map(usuario -> usuario.getIdUsuario().equals(idUsuario))
                .orElse(false);
    }

}

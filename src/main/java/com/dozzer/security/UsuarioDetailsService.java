
package com.dozzer.security;

import com.dozzer.entity.Usuario;
import com.dozzer.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.Set;

@Service
public class UsuarioDetailsService implements UserDetailsService {

 public static final String CAMBIO_PASSWORD_REQUERIDO = "CAMBIO_PASSWORD_REQUERIDO";

 @Autowired
 private UsuarioRepository usuarioRepository;

 @Override
 @Transactional(readOnly = true)
 public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {

  Usuario usuario = usuarioRepository.findByCorreoIgnoreCase(email)
          .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado"));

  Set<String> authorities = new HashSet<>();

  usuario.getRoles().forEach(rol -> {
   authorities.add("ROLE_" + rol.getNombre());

   rol.getPermisos().forEach(permiso ->
           authorities.add(permiso.getCodigo())
   );
  });

  // Marca interna: el JwtFilter bloquea todo excepto el cambio de contraseña
  if (Boolean.TRUE.equals(usuario.getDebeCambiarPassword())) {
   authorities.add(CAMBIO_PASSWORD_REQUERIDO);
  }

  return User.withUsername(usuario.getCorreo())
          .password(usuario.getPassword())
          .disabled(!Boolean.TRUE.equals(usuario.getActivo()))
          .authorities(authorities.toArray(new String[0]))
          .build();
 }
}

package com.dozzer.config;

import com.dozzer.entity.Usuario;
import com.dozzer.entity.Rol;
import com.dozzer.repository.UsuarioRepository;
import com.dozzer.repository.RolRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

@Component
public class InitialAdmin implements CommandLineRunner {

 @Autowired
 private UsuarioRepository usuarioRepository;

 @Autowired
 private RolRepository rolRepository;

 @Autowired
 private PasswordEncoder encoder;

 @Value("${app.admin.email}")
 private String email;

 @Value("${app.admin.password}")
 private String password;

 @Override
 @Transactional
 public void run(String... args) {
  if(usuarioRepository.findByCorreoIgnoreCase(email).isEmpty()) {
   Rol admin=rolRepository.findByNombre("ADMIN")
           .orElseThrow(()->new IllegalStateException("No existe el rol ADMIN"));

   Usuario usuario=new Usuario();
   usuario.setCorreo(email);
   usuario.setPassword(encoder.encode(password));
   usuario.setActivo(true);
   usuario.setRoles(Set.of(admin));

   usuarioRepository.save(usuario);
  }
 }
}

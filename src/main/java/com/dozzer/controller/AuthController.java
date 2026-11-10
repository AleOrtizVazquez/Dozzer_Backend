package com.dozzer.controller;

import com.dozzer.entity.Usuario;
import com.dozzer.repository.UsuarioRepository;
import com.dozzer.security.JwtService;
import com.dozzer.security.UsuarioDetailsService;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

 @Autowired
 private AuthenticationConfigurationHolder holder;

 @Autowired
 private JwtService jwt;

 @Autowired
 private UsuarioRepository usuarioRepository;

 @Autowired
 private PasswordEncoder passwordEncoder;

 @PostMapping("/login")
 @Transactional(readOnly = true)
 public Map<String,Object> login(@RequestBody LoginRequest req) {
  try {
   Authentication auth = holder.manager().authenticate(
           new UsernamePasswordAuthenticationToken(req.correo(),req.password())
   );

   String token = jwt.generate(auth.getName());

   List<String> roles = auth.getAuthorities().stream()
           .map(a -> a.getAuthority())
           .filter(a -> a.startsWith("ROLE_"))
           .map(a -> a.substring(5))
           .toList();

   List<String> permisos = auth.getAuthorities().stream()
           .map(a -> a.getAuthority())
           .filter(a -> !a.startsWith("ROLE_"))
           .filter(a -> !UsuarioDetailsService.CAMBIO_PASSWORD_REQUERIDO.equals(a))
           .toList();

   boolean debeCambiar = auth.getAuthorities().stream()
           .anyMatch(a -> UsuarioDetailsService.CAMBIO_PASSWORD_REQUERIDO.equals(a.getAuthority()));

   Map<String,Object> respuesta = new HashMap<>();
   respuesta.put("token",token);
   respuesta.put("correo",auth.getName());
   respuesta.put("roles",roles);
   respuesta.put("permisos",permisos);
   respuesta.put("debeCambiarPassword",debeCambiar);
   usuarioRepository.findByCorreoIgnoreCase(auth.getName())
           .map(u -> u.getPersona() != null ? u.getPersona().getNombreCompleto() : null)
           .ifPresent(nombre -> respuesta.put("nombre", nombre));

   return respuesta;

  } catch (AuthenticationException e) {
   throw new BadCredentialsException("Correo o contraseña incorrectos");
  } catch (Exception e) {
   throw new RuntimeException("Error al iniciar sesión",e);
  }
 }

 // Cambio de contraseña del usuario autenticado (obligatorio si tiene contraseña temporal)
 @PostMapping("/cambiar-password")
 @Transactional
 public Map<String,Object> cambiarPassword(@RequestBody Map<String,String> datos,
                                           Authentication authentication) {
  if (authentication == null) {
   throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
  }
  Usuario usuario = usuarioRepository.findByCorreoIgnoreCase(authentication.getName())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));

  String actual = datos == null ? null : datos.get("passwordActual");
  String nueva = datos == null ? null : datos.get("passwordNueva");

  if (actual == null || !passwordEncoder.matches(actual, usuario.getPassword())) {
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña actual no es correcta");
  }
  validarPasswordNueva(nueva);
  if (passwordEncoder.matches(nueva, usuario.getPassword())) {
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La nueva contraseña debe ser distinta a la actual");
  }

  usuario.setPassword(passwordEncoder.encode(nueva));
  usuario.setDebeCambiarPassword(false);
  usuarioRepository.save(usuario);

  return Map.of("success", true, "message", "Contraseña actualizada correctamente");
 }

 // "¿Olvidaste tu contraseña?" desde el login: la persona escribe su correo y una contraseña nueva.
 // Decisión del negocio: sin verificación adicional (solo el correo).
 // Las cuentas de ADMINISTRADOR quedan excluidas para que nadie tome el control del sistema así.
 @PostMapping("/recuperar-password")
 @Transactional
 public Map<String,Object> recuperarPassword(@RequestBody Map<String,String> datos) {
  String correo = datos == null || datos.get("correo") == null ? "" : datos.get("correo").trim();
  String numero = datos == null || datos.get("numeroEmpleado") == null ? "" : datos.get("numeroEmpleado").trim();
  String nueva = datos == null ? null : datos.get("passwordNueva");
  if (correo.isEmpty() || !correo.contains("@") || correo.length() > 180) {
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe un correo electrónico válido");
  }
  if (numero.isEmpty() || numero.length() > 20) {
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe tu número de empleado");
  }
  String llave = correo.toLowerCase();
  if (bloqueadoPorIntentos(llave)) {
   throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
           "Demasiados intentos. Espera una hora o pide ayuda al administrador.");
  }

  // Mismo mensaje si el correo no existe o el número no coincide (no revela qué cuentas existen)
  Usuario usuario = usuarioRepository.findByCorreoIgnoreCase(correo)
          .filter(u -> !Boolean.FALSE.equals(u.getActivo()))
          .filter(u -> u.getPersona() != null && u.getPersona().getNumeroEmpleado() != null
                  && normalizarNumero(u.getPersona().getNumeroEmpleado()).equals(normalizarNumero(numero)))
          .orElse(null);
  if (usuario == null) {
   registrarIntentoFallido(llave);
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
           "El correo y el número de empleado no coinciden con una cuenta activa");
  }

  boolean esAdmin = usuario.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getNombre()));
  if (esAdmin) {
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,
           "Las cuentas de administrador no pueden restablecerse desde aquí. Contacta al área de sistemas.");
  }

  validarPasswordNueva(nueva);
  intentosRecuperacion.remove(llave);
  usuario.setPassword(passwordEncoder.encode(nueva));
  usuario.setDebeCambiarPassword(false);
  usuarioRepository.save(usuario);

  return Map.of("success", true, "message", "Contraseña actualizada. Ya puedes entrar con ella.");
 }

 // ---- Límite de intentos para recuperar contraseña: 5 fallos por correo en 1 hora ----
 private static final int MAX_INTENTOS = 5;
 private static final long VENTANA_MS = 60 * 60 * 1000L;
 private final java.util.concurrent.ConcurrentHashMap<String, java.util.Deque<Long>> intentosRecuperacion =
         new java.util.concurrent.ConcurrentHashMap<>();

 private boolean bloqueadoPorIntentos(String llave) {
  java.util.Deque<Long> d = intentosRecuperacion.get(llave);
  if (d == null) return false;
  synchronized (d) {
   long limite = System.currentTimeMillis() - VENTANA_MS;
   while (!d.isEmpty() && d.peekFirst() < limite) d.pollFirst();
   return d.size() >= MAX_INTENTOS;
  }
 }

 private void registrarIntentoFallido(String llave) {
  if (intentosRecuperacion.size() > 10000) intentosRecuperacion.clear();
  java.util.Deque<Long> d = intentosRecuperacion.computeIfAbsent(llave, k -> new java.util.ArrayDeque<>());
  synchronized (d) { d.addLast(System.currentTimeMillis()); }
 }

 // "dz-0001", "DZ 0001" y "DZ0001" cuentan igual
 private static String normalizarNumero(String n) {
  return n == null ? "" : n.replaceAll("[\\s-]", "").toUpperCase();
 }

 private void validarPasswordNueva(String nueva) {
  if (nueva == null || nueva.length() < 8) {
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña debe tener al menos 8 caracteres");
  }
  if (nueva.length() > 72) {
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña no puede superar 72 caracteres");
  }
  if (!nueva.matches(".*[A-Za-zÁÉÍÓÚÑáéíóúñ].*") || !nueva.matches(".*\\d.*")) {
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña debe incluir letras y números");
  }
 }

 public record LoginRequest(
         @Email @NotBlank String correo,
         @NotBlank String password
 ) {}
}

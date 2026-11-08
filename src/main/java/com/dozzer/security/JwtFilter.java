package com.dozzer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class JwtFilter extends OncePerRequestFilter {

 @Autowired
 private JwtService jwt;

 @Autowired
 private UserDetailsService users;

 @Override
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
  String h=req.getHeader("Authorization");
  if(h!=null&&h.startsWith("Bearer ")) {
   try {
    String email=jwt.subject(h.substring(7));
    if(SecurityContextHolder.getContext().getAuthentication()==null) {
     var user=users.loadUserByUsername(email);
     var auth=new UsernamePasswordAuthenticationToken(user,null,user.getAuthorities());
     auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
     SecurityContextHolder.getContext().setAuthentication(auth);
    }
   } catch(Exception ignored) {
    SecurityContextHolder.clearContext();
   }
  }

  if (requiereCambioPassword() && !rutaPermitidaSinCambio(req)) {
   res.setStatus(HttpServletResponse.SC_FORBIDDEN);
   res.setContentType("application/json;charset=UTF-8");
   res.getWriter().write("{\"error\":\"Debes cambiar tu contraseña temporal antes de continuar\","
           + "\"codigo\":\"" + UsuarioDetailsService.CAMBIO_PASSWORD_REQUERIDO + "\"}");
   return;
  }

  chain.doFilter(req,res);
 }

 // El usuario autenticado tiene una contraseña temporal pendiente de cambio
 private boolean requiereCambioPassword() {
  var auth = SecurityContextHolder.getContext().getAuthentication();
  return auth != null && auth.getAuthorities().stream()
          .anyMatch(a -> UsuarioDetailsService.CAMBIO_PASSWORD_REQUERIDO.equals(a.getAuthority()));
 }

 private boolean rutaPermitidaSinCambio(HttpServletRequest req) {
  String ruta = req.getRequestURI();
  return "OPTIONS".equalsIgnoreCase(req.getMethod())
          || ruta.equals("/api/auth/login")
          || ruta.equals("/api/auth/recuperar-password")
          || ruta.equals("/api/auth/cambiar-password");
 }
}

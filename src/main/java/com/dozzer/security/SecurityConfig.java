package com.dozzer.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

 @Bean
 public PasswordEncoder passwordEncoder() {
  return new BCryptPasswordEncoder();
 }

 @Bean
 public SecurityFilterChain filterChain(
         HttpSecurity http,
         JwtFilter jwt,
         @Value("${app.cors.allowed-origin}") String origin) throws Exception {

  http.csrf(c->c.disable())
          .cors(c->c.configurationSource(r->{
           CorsConfiguration x=new CorsConfiguration();
           // Admite varias direcciones separadas por coma (ej. localhost y la IP de la red local)
           x.setAllowedOrigins(java.util.Arrays.stream(origin.split(","))
                   .map(String::trim).filter(o -> !o.isEmpty()).toList());
           x.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
           x.setAllowedHeaders(List.of("*"));
           x.setAllowCredentials(true);
           return x;
          }))
          .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(a->a
                  .requestMatchers("/api/auth/login", "/api/auth/recuperar-password", "/api/salud").permitAll()
                  .anyRequest().authenticated()
          )
          .addFilterBefore(jwt,UsernamePasswordAuthenticationFilter.class);

  return http.build();
 }
}
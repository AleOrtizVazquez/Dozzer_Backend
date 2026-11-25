package com.dozzer.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

 private final SecretKey key;
 private final long expiration;

 public JwtService(
         @Value("${app.jwt.secret}") String secret,
         @Value("${app.jwt.expiration-minutes}") long minutes) {
  this.key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  this.expiration=minutes*60_000;
 }

 public String generate(String subject) {
  return generate(subject, expiration);
 }

 // Sesión más larga (ej. responsables de vehículo: entran una vez en su celular)
 public String generate(String subject, long milisegundos) {
  long now=System.currentTimeMillis();
  return Jwts.builder()
          .subject(subject)
          .issuedAt(new Date(now))
          .expiration(new Date(now+milisegundos))
          .signWith(key)
          .compact();
 }

 public String subject(String token) {
  return Jwts.parser()
          .verifyWith(key)
          .build()
          .parseSignedClaims(token)
          .getPayload()
          .getSubject();
 }
}

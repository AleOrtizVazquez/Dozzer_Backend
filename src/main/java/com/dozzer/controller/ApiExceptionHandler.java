package com.dozzer.controller;

import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

 // Errores de negocio lanzados con un código HTTP explícito (409, 403, 404...)
 @ExceptionHandler(ResponseStatusException.class)
 public ResponseEntity<Map<String,String>> handleStatus(ResponseStatusException ex) {
  String mensaje = ex.getReason() == null ? "Error en la solicitud" : ex.getReason();
  return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", mensaje));
 }

 // Falta de permisos en @PreAuthorize
 @ExceptionHandler(AccessDeniedException.class)
 public ResponseEntity<Map<String,String>> handleAccessDenied(AccessDeniedException ex) {
  return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
          Map.of("error", "No tienes permiso para realizar esta acción"));
 }

 // Duplicados o referencias inválidas detectadas por la base de datos
 @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
 public ResponseEntity<Map<String,String>> handleIntegridad(Exception ex) {
  return ResponseEntity.status(HttpStatus.CONFLICT).body(
          Map.of("error", "La operación no se pudo completar: el registro ya existe o está en uso"));
 }

 @ExceptionHandler(Exception.class)
 public ResponseEntity<Map<String,String>> handle(Exception ex) {
  return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
          Map.of("error",ex.getMessage()==null?"Error en la solicitud":ex.getMessage())
  );
 }
}

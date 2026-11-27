package com.dozzer.controller;

import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

 private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);

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

 // Ruta que el servidor no conoce: casi siempre es una función nueva que Render todavía no publica
 @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
 public ResponseEntity<Map<String,String>> handleNoExiste(Exception ex) {
  log.warn("Ruta inexistente: {}", ex.getMessage());
  return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
          Map.of("error", "Esta función todavía no está publicada en el servidor. Espera unos minutos y recarga."));
 }

 @ExceptionHandler(Exception.class)
 public ResponseEntity<Map<String,String>> handle(Exception ex) {
  // Se registra completo en los logs de Render para poder encontrar la causa
  log.error("Error no controlado", ex);
  String msg = ex.getMessage() == null ? "sin detalle" : ex.getMessage();
  if (msg.length() > 300) msg = msg.substring(0, 300) + "…";
  return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
          Map.of("error", "Error inesperado (" + ex.getClass().getSimpleName() + "): " + msg));
 }
}

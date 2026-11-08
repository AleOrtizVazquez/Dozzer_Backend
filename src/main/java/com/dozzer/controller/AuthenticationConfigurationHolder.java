package com.dozzer.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;

@Component
public class AuthenticationConfigurationHolder {
 @Autowired
 private AuthenticationConfiguration configuration;

 public AuthenticationManager manager() {
  try {
   return configuration.getAuthenticationManager();
  } catch (Exception e) {
   throw new IllegalStateException("No se pudo obtener el AuthenticationManager",e);
  }
 }
}

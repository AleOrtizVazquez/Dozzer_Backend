package com.dozzer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication
@EnableScheduling
public class DozzerApplication {
 public static void main(String[] args) { SpringApplication.run(DozzerApplication.class, args); }
}

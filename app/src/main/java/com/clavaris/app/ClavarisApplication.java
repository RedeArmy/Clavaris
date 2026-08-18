package com.clavaris.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single deployable entry point (system-design-document.md §3 — modular monolith, one process, not
 * one per module). This class intentionally contains nothing but bootstrap: component scanning
 * across com.clavaris.* picks up each module's own infrastructure/config beans without this module
 * needing to know their shape.
 */
@SpringBootApplication(scanBasePackages = "com.clavaris")
public class ClavarisApplication {

  public static void main(final String[] args) {
    SpringApplication.run(ClavarisApplication.class, args);
  }
}

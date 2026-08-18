package com.clavaris.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Single deployable entry point (system-design-document.md §3 — modular monolith, one process, not
 * one per module). This class intentionally contains nothing but bootstrap: component scanning
 * across com.clavaris.* picks up each module's own infrastructure/config beans without this module
 * needing to know their shape.
 *
 * <p>{@code scanBasePackages} only widens {@code @ComponentScan} — {@code @EnableJpaRepositories}
 * and {@code @EntityScan} are separate mechanisms that otherwise default to this class's own
 * package ({@code com.clavaris.app}), not {@code scanBasePackages}. Confirmed live: without the two
 * annotations below, {@code identity-module}'s {@code SpringDataAccountJpaRepository} (under {@code
 * com.clavaris.identity.*}, a different top-level package) had no bean at all — {@code
 * NoSuchBeanDefinitionException} — despite {@code @ComponentScan} correctly finding the
 * {@code @Repository} class that depends on it. This was a silent, latent gap since the very first
 * commit; nothing exercised it until this module's first {@code @Entity} and Spring Data repository
 * existed to prove it.
 */
@SpringBootApplication(scanBasePackages = "com.clavaris")
@EnableJpaRepositories(basePackages = "com.clavaris")
@EntityScan(basePackages = "com.clavaris")
public class ClavarisApplication {

  public static void main(final String[] args) {
    SpringApplication.run(ClavarisApplication.class, args);
  }
}

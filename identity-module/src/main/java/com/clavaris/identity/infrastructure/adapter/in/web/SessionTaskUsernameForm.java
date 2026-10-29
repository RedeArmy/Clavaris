package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Size;

/**
 * Web-layer form object for {@link SessionTaskUsernameController} (ADR-0031). The account is
 * identified by the pending session state {@link SessionTaskGate} established, never by anything
 * submitted. The shape itself (3-32 letters, digits, underscore, hyphen) is enforced by {@code
 * Username}'s own constructor, not duplicated here, like {@link RegisterAccountForm#getUsername}.
 */
public class SessionTaskUsernameForm {

  @Size(max = 32, message = "Username must be at most 32 characters")
  private String username;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public SessionTaskUsernameForm() {
    // Intentionally empty — Spring MVC/Thymeleaf just need a no-arg constructor to bind
    // form-submitted values onto via the setter below.
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(final String username) {
    this.username = username;
  }
}

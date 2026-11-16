package com.clavaris.clientregistry.domain.model;

/**
 * ADR-0043: the platform has switched {@code clavaris.oauth.pkce.allow-optional} off, so no client
 * can be registered with, or moved to, {@link PkcePolicy#OPTIONAL}. An {@link
 * IllegalArgumentException} so the dashboard, which already answers one with a 400, needs no new
 * branch; the REST registration endpoint maps it itself.
 */
public class OptionalPkceNotAllowedException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public OptionalPkceNotAllowedException() {
    super("PKCE cannot be made optional: this platform requires it for every client.");
  }
}

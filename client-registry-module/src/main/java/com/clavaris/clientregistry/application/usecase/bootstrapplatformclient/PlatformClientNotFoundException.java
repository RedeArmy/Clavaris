package com.clavaris.clientregistry.application.usecase.bootstrapplatformclient;

/** Same rationale as organization-module's own equivalent — never a dangling reference. */
public final class PlatformClientNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PlatformClientNotFoundException(final String clientId) {
    super("No PlatformClient exists with client_id " + clientId);
  }
}

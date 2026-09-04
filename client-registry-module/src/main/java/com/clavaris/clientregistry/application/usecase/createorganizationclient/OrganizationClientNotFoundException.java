package com.clavaris.clientregistry.application.usecase.createorganizationclient;

/** Same rationale as {@code bootstrapplatformclient.PlatformClientNotFoundException}. */
public final class OrganizationClientNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationClientNotFoundException(final String clientId) {
    super("No OrganizationClient exists with clientId " + clientId);
  }
}

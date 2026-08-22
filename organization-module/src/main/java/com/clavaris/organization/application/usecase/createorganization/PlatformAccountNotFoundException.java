package com.clavaris.organization.application.usecase.createorganization;

import java.util.UUID;

/**
 * ADR-0012: an {@code Organization} must be owned by a real {@code PlatformAccount} — never a
 * dangling reference, same rationale as client-registry-module's own {@code
 * OrganizationNotFoundException} for {@code OAuthClient}/{@code Organization}.
 */
public final class PlatformAccountNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PlatformAccountNotFoundException(final UUID platformAccountId) {
    super("No PlatformAccount exists with id " + platformAccountId);
  }
}

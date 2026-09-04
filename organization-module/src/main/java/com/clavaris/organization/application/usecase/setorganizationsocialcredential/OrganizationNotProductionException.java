package com.clavaris.organization.application.usecase.setorganizationsocialcredential;

import java.util.UUID;

/**
 * ADR-0022: bringing your own OAuth app credentials is scoped to {@code PRODUCTION} Organizations
 * only — a {@code DEVELOPMENT} sandbox always uses the shared Clavaris app, no exceptions (matches
 * this feature's own reason for existing: a tenant wanting its own branded Google/GitHub consent
 * screen is a production concern, not something a throwaway sandbox needs).
 */
public final class OrganizationNotProductionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationNotProductionException(final UUID organizationId) {
    super("Organization " + organizationId + " is not a PRODUCTION environment");
  }
}

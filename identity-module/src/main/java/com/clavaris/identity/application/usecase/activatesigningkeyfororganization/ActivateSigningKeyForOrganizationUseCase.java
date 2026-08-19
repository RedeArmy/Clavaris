package com.clavaris.identity.application.usecase.activatesigningkeyfororganization;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;

/**
 * Inbound port. Called once, synchronously, as part of {@code CreateOrganization} (BR-ORG-06) — and
 * reusable, unchanged, as the manual rotation operation ADR-0010 §5.2 describes for v1 (invoked
 * again later with a freshly-generated {@code kid} for the same Organization). Only records
 * metadata (CLAUDE.md §6, audit trail) — the actual key material is generated separately, by {@code
 * infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory}, before this is
 * called.
 */
@FunctionalInterface
public interface ActivateSigningKeyForOrganizationUseCase {

  SigningKey handle(OrganizationId organizationId, String kid, String algorithm);
}

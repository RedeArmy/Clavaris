package com.clavaris.identity.application.usecase.rotatesigningkeyfororganization;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Outbound port — generates fresh RSA key material for {@code organizationId} and returns its
 * {@code kid}, without touching any metadata row (that's {@code
 * ActivateSigningKeyForOrganizationUseCase}'s own job, called separately). Deliberately doesn't
 * reference {@code infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory}
 * directly from this application-layer service — the hexagonal dependency rule (CLAUDE.md §7.2)
 * applies within one module too, not only across module boundaries. {@code
 * OrganizationSigningKeyMaterialFactory} implements this port directly; its own {@code
 * generateFor(OrganizationId)} method already has this exact shape, so no separate bridge class is
 * needed the way {@code CreateOrganizationSigningKeyBridge} was for the cross-module case.
 */
@FunctionalInterface
public interface SigningKeyMaterialGenerator {

  String generateFor(OrganizationId organizationId);
}

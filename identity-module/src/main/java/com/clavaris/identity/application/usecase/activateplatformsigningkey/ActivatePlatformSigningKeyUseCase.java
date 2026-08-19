package com.clavaris.identity.application.usecase.activateplatformsigningkey;

import com.clavaris.identity.domain.model.PlatformSigningKey;

/**
 * Inbound port, called once at startup after the platform issuer's in-memory RSA key pair is
 * generated (see {@code infrastructure.adapter.out.security.PlatformSigningKeyMaterial}) — this
 * only records the metadata (CLAUDE.md §6, audit trail for the system's single highest-value
 * credential), it never generates or holds the actual key material.
 */
@FunctionalInterface
public interface ActivatePlatformSigningKeyUseCase {

  PlatformSigningKey handle(String kid, String algorithm);
}

package com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes;

import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;

/**
 * Live UX request, 2026-09-24: reverses BR-ORG-06's original "creation-time-only" rule for {@code
 * allowedGrantTypes} — same required, non-nullable {@code organizationId} shape every other
 * OAuthClient command in this module already carries, for the identical anti-enumeration reason.
 */
public record UpdateOAuthClientGrantTypesCommand(
    String clientId, UUID organizationId, List<String> allowedGrantTypes, AuditActor actor) {}

package com.clavaris.clientregistry.application.usecase.deactivateoauthclient;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * Same shape as {@code deactivateorganizationclient.DeactivateOrganizationClientCommand}. {@code
 * clientId}, not {@code id} — this credential's own primary lookup key everywhere else in this
 * module (see {@code OAuthClientRepository#findByClientId}).
 *
 * <p>SDE-III review, 2026-09-15 — real gap found and closed: {@code organizationId} used to be
 * absent entirely, so the tenant boundary for this mutation was enforced only by {@code
 * PlatformOAuthClientController}'s own former {@code requireClientIdBelongsToOrganization}
 * workaround (listing every client already scoped to the caller's Organization, then checking
 * membership, before this command was ever built) — a future caller that skipped that step had
 * nothing else stopping it. Required here, not nullable like the Secret-Key sibling: this command's
 * only caller is the dashboard, which always has a real Organization context; no platform-tier REST
 * endpoint exists for this credential's deactivation. {@link DeactivateOAuthClientService} now
 * verifies ownership itself.
 */
public record DeactivateOAuthClientCommand(
    String clientId, UUID organizationId, AuditActor actor) {}

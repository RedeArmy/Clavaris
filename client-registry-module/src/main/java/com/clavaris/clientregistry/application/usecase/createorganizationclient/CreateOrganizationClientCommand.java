package com.clavaris.clientregistry.application.usecase.createorganizationclient;

import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;

/**
 * ADR-0023, same shape as {@code RegisterOAuthClientCommand} — {@code allowedScopes} is
 * caller-supplied (not always {@code PlatformScopes.BOOTSTRAP_DEFAULT} the way the platform
 * bootstrap client is) so a caller can mint a narrowly-scoped Secret Key for one Organization
 * rather than always granting every reachable capability.
 *
 * @param actor either a {@link AuditActor#platformClient} actor (an operator calling {@code
 *     /api/v1/admin/**}) or, since ADR-0025's own dashboard started calling this same use case, a
 *     {@link AuditActor#platformAccount} actor — genuine self-service by the Organization's own
 *     owning {@code PlatformAccount} minting its own Secret Key, same widening {@code
 *     CreateWorkspaceCommand}'s own Javadoc already documents for an identical situation (Clerk's
 *     own dashboard lets an account holder generate their own API keys the same way). Never a
 *     PlatformAccount minting a key for an Organization it doesn't own — the dashboard's own
 *     ownership check happens before this command is ever built, not inside this use case.
 */
public record CreateOrganizationClientCommand(
    UUID organizationId, List<String> allowedScopes, AuditActor actor) {}

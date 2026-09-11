package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;

/**
 * No secret to redact here (unlike {@code BootstrapPlatformClientCommand}) — {@code
 * clientId}/{@code rawClientSecret} are generated inside {@link RegisterOAuthClientService}, never
 * supplied by the caller. A machine credential is stronger generated server-side than accepted from
 * an operator's own (potentially weak) choice.
 *
 * @param requireConsent TD-SEC-026/ADR-0017: resolved by the web adapter, which defaults an absent
 *     request field to {@code true} — this command always carries an explicit value, never an
 *     implicit one.
 * @param postLogoutRedirectUris TD-FUT-018: resolved by the web adapter, which defaults an absent
 *     request field to an empty list — same "always explicit, never implicit" discipline as {@code
 *     requireConsent} above. Empty means "not configured," not an error.
 * @param actor SDE-III review, 2026-09-11: added alongside the dashboard's own real {@code
 *     OAuthClient} registration page — before this, this command carried no actor at all and {@link
 *     RegisterOAuthClientService} audited nothing, the one create-shaped use case in this module
 *     that didn't (confirmed: every sibling — {@code CreateOrganizationClientCommand}, {@code
 *     CreateWorkspaceCommand} — already did). Closed as a real, pre-existing gap, not left
 *     unaudited just because the only caller used to be the operator-only REST API. Either a {@link
 *     AuditActor#platformClient} actor (an operator calling {@code /api/v1/admin/**}) or a {@link
 *     AuditActor#platformAccount} actor (the dashboard's own session-authenticated caller, same
 *     self-service widening {@code CreateOrganizationClientCommand}'s own Javadoc already documents
 *     for the identical situation) — the dashboard's own ownership check happens before this
 *     command is ever built, not inside this use case.
 */
// PMD.LongVariable: postLogoutRedirectUris is the exact OIDC spec term (post_logout_redirect_uris)
// — same "the name is right, not arbitrarily long" precedent as PlatformScopes' own suppression.
@SuppressWarnings("PMD.LongVariable")
public record RegisterOAuthClientCommand(
    UUID organizationId,
    List<String> redirectUris,
    List<String> allowedGrantTypes,
    List<String> allowedScopes,
    boolean requireConsent,
    List<String> postLogoutRedirectUris,
    AuditActor actor) {}

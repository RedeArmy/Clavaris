package com.clavaris.clientregistry.application.usecase.registeroauthclient;

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
    List<String> postLogoutRedirectUris) {}

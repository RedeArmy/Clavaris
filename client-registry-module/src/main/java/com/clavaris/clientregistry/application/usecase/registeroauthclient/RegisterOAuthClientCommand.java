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
 */
public record RegisterOAuthClientCommand(
    UUID organizationId,
    List<String> redirectUris,
    List<String> allowedGrantTypes,
    List<String> allowedScopes,
    boolean requireConsent) {}

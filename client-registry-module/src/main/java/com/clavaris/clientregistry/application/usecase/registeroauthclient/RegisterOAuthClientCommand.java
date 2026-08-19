package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import java.util.List;
import java.util.UUID;

/**
 * No secret to redact here (unlike {@code BootstrapPlatformClientCommand}) — {@code
 * clientId}/{@code rawClientSecret} are generated inside {@link RegisterOAuthClientService}, never
 * supplied by the caller. A machine credential is stronger generated server-side than accepted from
 * an operator's own (potentially weak) choice.
 */
public record RegisterOAuthClientCommand(
    UUID organizationId,
    List<String> redirectUris,
    List<String> allowedGrantTypes,
    List<String> allowedScopes) {}

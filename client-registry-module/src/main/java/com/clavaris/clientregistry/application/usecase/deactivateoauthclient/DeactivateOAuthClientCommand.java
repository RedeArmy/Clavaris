package com.clavaris.clientregistry.application.usecase.deactivateoauthclient;

import com.clavaris.common.domain.model.AuditActor;

/**
 * Same shape as {@code deactivateorganizationclient.DeactivateOrganizationClientCommand}. {@code
 * clientId}, not {@code id}/{@code organizationId} — this credential's own primary lookup key
 * everywhere else in this module (see {@code OAuthClientRepository#findByClientId}). Carries no
 * {@code organizationId} of its own, same as its sibling: the dashboard controller resolves and
 * verifies ownership of the target client before this command is ever built (see {@code
 * PlatformOAuthClientController}'s own Javadoc).
 */
public record DeactivateOAuthClientCommand(String clientId, AuditActor actor) {}

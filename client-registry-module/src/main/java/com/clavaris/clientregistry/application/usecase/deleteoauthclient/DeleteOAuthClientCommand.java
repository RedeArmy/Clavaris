package com.clavaris.clientregistry.application.usecase.deleteoauthclient;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * Live UX request, 2026-09-24: permanent, irreversible deletion of an OAuthClient — only ever
 * allowed while the client is already deactivated ({@link OAuthClientActiveException}), so an owner
 * can never accidentally destroy a client still in use. {@code organizationId} is required, not
 * nullable, same anti-enumeration reasoning as every other OAuthClient command in this module.
 */
public record DeleteOAuthClientCommand(String clientId, UUID organizationId, AuditActor actor) {}

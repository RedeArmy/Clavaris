package com.clavaris.clientregistry.application.usecase.activateoauthclient;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * Same shape as {@code deactivateoauthclient.DeactivateOAuthClientCommand} — {@code organizationId}
 * is required, not nullable, for the identical anti-enumeration reason that class's own Javadoc
 * documents (this command's only caller is the dashboard, which always has a real Organization
 * context).
 */
public record ActivateOAuthClientCommand(String clientId, UUID organizationId, AuditActor actor) {}

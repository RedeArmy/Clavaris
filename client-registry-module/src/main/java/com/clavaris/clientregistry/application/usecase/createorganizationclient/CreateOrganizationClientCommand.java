package com.clavaris.clientregistry.application.usecase.createorganizationclient;

import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;

/**
 * ADR-0023: operator-managed only in v1 (no tenant self-service), same shape as {@code
 * RegisterOAuthClientCommand} — {@code allowedScopes} is caller-supplied (not always {@code
 * PlatformScopes.BOOTSTRAP_DEFAULT} the way the platform bootstrap client is) so an operator can
 * mint a narrowly-scoped Secret Key for one Organization rather than always granting every
 * reachable capability.
 */
public record CreateOrganizationClientCommand(
    UUID organizationId, List<String> allowedScopes, AuditActor actor) {}

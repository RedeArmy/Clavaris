package com.clavaris.clientregistry.application.usecase.deleteorganizationclient;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * Permanent, irreversible deletion of a Secret Key - only ever allowed while it is already
 * deactivated ({@link OrganizationClientActiveException}), so an owner cannot destroy a credential
 * still in use. {@code organizationId} is required, same anti-enumeration reasoning as every other
 * dashboard-scoped command in this module.
 */
public record DeleteOrganizationClientCommand(
    String clientId, UUID organizationId, AuditActor actor) {}

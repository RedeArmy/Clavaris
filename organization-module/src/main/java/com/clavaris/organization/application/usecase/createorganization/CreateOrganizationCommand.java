package com.clavaris.organization.application.usecase.createorganization;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * No secrets in this command (unlike {@code RegisterAccountCommand}/{@code
 * BootstrapPlatformClientCommand}) — the default {@code toString()} is safe as-is.
 *
 * @param ownerPlatformAccountId ADR-0012 — always required, resolved either from the
 *     session-authenticated dashboard's own principal or, for the operator/{@code PlatformClient}
 *     REST path, supplied explicitly in the request body.
 * @param actor TD-SEC-007: who is actually making this call, resolved by the controller from the
 *     request's own {@code Authentication} — the dashboard path's actor is always the same {@code
 *     PlatformAccount} as {@code ownerPlatformAccountId} (self-service), the REST path's actor is
 *     the calling {@code PlatformClient}, which may differ from the Organization's eventual owner
 *     (an operator provisioning on someone else's behalf).
 */
@SuppressWarnings("PMD.LongVariable")
public record CreateOrganizationCommand(
    String name, UUID ownerPlatformAccountId, AuditActor actor) {}

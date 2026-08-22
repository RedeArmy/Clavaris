package com.clavaris.organization.application.usecase.createorganization;

import java.util.UUID;

/**
 * No secrets in this command (unlike {@code RegisterAccountCommand}/{@code
 * BootstrapPlatformClientCommand}) — the default {@code toString()} is safe as-is.
 *
 * @param ownerPlatformAccountId ADR-0012 — always required, resolved either from the
 *     session-authenticated dashboard's own principal or, for the operator/{@code PlatformClient}
 *     REST path, supplied explicitly in the request body.
 */
@SuppressWarnings("PMD.LongVariable")
public record CreateOrganizationCommand(String name, UUID ownerPlatformAccountId) {}

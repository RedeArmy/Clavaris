package com.clavaris.organization.application.usecase.createorganization;

/**
 * No secrets in this command (unlike {@code RegisterAccountCommand}/{@code
 * BootstrapPlatformClientCommand}) — the default {@code toString()} is safe as-is.
 */
public record CreateOrganizationCommand(String name) {}

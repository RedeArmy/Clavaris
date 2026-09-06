package com.clavaris.clientregistry.infrastructure.adapter.in.web;

/**
 * HTTP request body for {@code PUT
 * /api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/branding}. Every field is
 * nullable — an omitted field means "leave unconfigured," never "clear to blank."
 */
// PMD.LongVariable: same SetClientBrandingCommand precedent — applicationDisplayName names
// exactly what ADR-0009 §3 itself calls the field, not arbitrarily long.
@SuppressWarnings("PMD.LongVariable")
public record SetClientBrandingRequest(
    String logoUrl, String primaryColor, String applicationDisplayName) {}

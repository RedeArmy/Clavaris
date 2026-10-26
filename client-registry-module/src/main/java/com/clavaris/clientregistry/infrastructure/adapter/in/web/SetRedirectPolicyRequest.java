package com.clavaris.clientregistry.infrastructure.adapter.in.web;

/**
 * HTTP request body for {@code PUT
 * /api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/redirect-policy}. Every
 * field is nullable — an omitted field means "leave unconfigured," never "clear to blank" (the same
 * omitted-field-is-meaningful discipline {@code RegisterOAuthClientRequest} already applies to
 * {@code requireConsent}), resolved by the service passing {@code null} straight through, not here.
 */
public record SetRedirectPolicyRequest(
    String fallbackSignInRedirectUrl,
    String fallbackSignUpRedirectUrl,
    String forceSignInRedirectUrl,
    String forceSignUpRedirectUrl) {}

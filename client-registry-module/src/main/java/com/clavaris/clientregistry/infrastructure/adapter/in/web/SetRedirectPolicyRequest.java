package com.clavaris.clientregistry.infrastructure.adapter.in.web;

/**
 * HTTP request body for {@code PUT
 * /api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/redirect-policy}. Every
 * field is nullable — an omitted field means "leave unconfigured," never "clear to blank" (the same
 * omitted-field-is-meaningful discipline {@code RegisterOAuthClientRequest} already applies to
 * {@code requireConsent}), resolved by the service passing {@code null} straight through, not here.
 */
// PMD.LongVariable: same OAuthClient/RedirectPolicy precedent — these names match Clerk's own
// equivalent concept, not arbitrarily long.
@SuppressWarnings("PMD.LongVariable")
public record SetRedirectPolicyRequest(
    String fallbackSignInRedirectUrl,
    String fallbackSignUpRedirectUrl,
    String forceSignInRedirectUrl,
    String forceSignUpRedirectUrl) {}

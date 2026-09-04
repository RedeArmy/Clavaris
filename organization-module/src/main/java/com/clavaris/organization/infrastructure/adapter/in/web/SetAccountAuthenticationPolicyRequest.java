package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.EmailVerificationMethod;
import jakarta.validation.constraints.NotNull;

/**
 * HTTP request body for {@code PUT
 * /api/v1/admin/organizations/{organizationId}/authentication-policy} (ADR-0024). Email/password
 * sign-up/sign-in themselves are deliberately absent — see {@code AccountAuthenticationPolicy}'s
 * own Javadoc for why they are permanently on, not a field here.
 */
@SuppressWarnings({"java:S107", "PMD.LongVariable"})
public record SetAccountAuthenticationPolicyRequest(
    boolean emailVerificationRequiredAtSignIn,
    @NotNull EmailVerificationMethod emailVerificationMethod,
    boolean emailCodeSignInEnabled,
    boolean emailLinkSignInEnabled,
    boolean usernameSignUpEnabled,
    boolean usernameRequired,
    boolean usernameSignInEnabled,
    boolean passwordAtSignUpEnabled,
    boolean deviceTrustEnabled) {}

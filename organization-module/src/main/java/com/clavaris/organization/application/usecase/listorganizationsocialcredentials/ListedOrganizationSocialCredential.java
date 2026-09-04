package com.clavaris.organization.application.usecase.listorganizationsocialcredentials;

import com.clavaris.organization.domain.model.SocialProvider;
import java.time.Instant;

/**
 * Deliberately excludes {@code clientSecretEncrypted} — the secret never round-trips out of this
 * system in any form, not even encrypted, once written (stricter than {@code OAuthClient}'s own
 * "raw secret returned once, at creation" convention: this secret isn't Clavaris-generated, so
 * there is no onboarding moment that needs it echoed back at all).
 */
public record ListedOrganizationSocialCredential(
    SocialProvider provider, String clientId, Instant updatedAt) {}

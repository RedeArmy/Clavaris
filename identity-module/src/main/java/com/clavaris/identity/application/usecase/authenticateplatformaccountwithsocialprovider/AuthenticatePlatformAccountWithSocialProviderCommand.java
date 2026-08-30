package com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.SocialProvider;

/**
 * {@link
 * com.clavaris.identity.application.usecase.authenticatewithsocialprovider.AuthenticateWithSocialProviderCommand}'s
 * platform-tier sibling — no {@code organizationId} (a {@code PlatformAccount}'s email is globally
 * unique, same scoping-free shape {@code AuthenticatePlatformAccountWithPasswordCommand} already
 * establishes).
 *
 * @param emailVerifiedByProvider same defense-in-depth rationale as the tenant-tier command.
 */
// PMD.LongVariable: emailVerifiedByProvider names exactly what it is, same convention the
// tenant-tier sibling command's own identical suppression documents.
@SuppressWarnings("PMD.LongVariable")
public record AuthenticatePlatformAccountWithSocialProviderCommand(
    SocialProvider provider, String providerUserId, Email email, boolean emailVerifiedByProvider) {}

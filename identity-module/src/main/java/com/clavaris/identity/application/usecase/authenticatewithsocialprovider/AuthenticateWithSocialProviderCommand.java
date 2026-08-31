package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;

/**
 * Input to {@link AuthenticateWithSocialProviderUseCase}. Built by the (Phase 4, not yet built)
 * OAuth2 client adapter after it has already exchanged the provider's authorization code and
 * decoded the returned identity — this use case never talks to Google/GitHub itself (§7.2's
 * dependency rule: no HTTP concepts in {@code application/}).
 *
 * @param providerUserId the provider's own opaque, stable subject identifier — never the email (see
 *     {@code SocialIdentity}'s own Javadoc for why).
 * @param email the account-holder's email as reported by the provider.
 * @param emailVerifiedByProvider ADR-0020 Decision 1's whole linking design assumes this is
 *     trustworthy — {@link AuthenticateWithSocialProviderService} refuses to proceed at all when
 *     this is {@code false} (defense in depth: the adapter should already filter these out, but the
 *     use case does not trust a caller-supplied claim of "verified" without checking it itself).
 */
// PMD.LongVariable: emailVerifiedByProvider names exactly what it is — a shortened identifier
// would only make this record harder to read, same convention every other descriptively-named
// field in this codebase follows (e.g. PendingSocialLink's own confirmationTokenHash).
@SuppressWarnings("PMD.LongVariable")
public record AuthenticateWithSocialProviderCommand(
    OrganizationId organizationId,
    SocialProvider provider,
    String providerUserId,
    Email email,
    boolean emailVerifiedByProvider) {}

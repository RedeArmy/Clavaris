package com.clavaris.organization.application.usecase.setorganizationsocialcredential;

import com.clavaris.organization.domain.model.SocialProvider;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/crypto/AesGcmOrganizationSocialCredentialCipher}. Reversible
 * encryption, deliberately not a one-way hash like {@code OAuthClient.clientSecretHash}
 * (client-registry-module): Clavaris must present this cleartext secret outbound to Google/GitHub
 * on every token exchange, the opposite shape from a machine credential Clavaris only ever verifies
 * against a presented value — same distinction {@code WebhookSigningSecretCipher}'s own Javadoc
 * (webhook-module) already states for an identical need.
 *
 * <p>Each secret is bound to its Organization and provider (TD-SEC-086): one Organization's secret
 * copied into another's row, or Google's into GitHub's, does not decrypt.
 */
public interface OrganizationSocialCredentialCipher {

  String encrypt(UUID organizationId, SocialProvider provider, String rawClientSecret);

  String decrypt(UUID organizationId, SocialProvider provider, String encryptedClientSecret);
}

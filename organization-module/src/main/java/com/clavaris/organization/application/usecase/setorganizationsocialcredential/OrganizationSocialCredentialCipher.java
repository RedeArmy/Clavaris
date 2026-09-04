package com.clavaris.organization.application.usecase.setorganizationsocialcredential;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/crypto/AesGcmOrganizationSocialCredentialCipher}. Reversible
 * encryption, deliberately not a one-way hash like {@code OAuthClient.clientSecretHash}
 * (client-registry-module): Clavaris must present this cleartext secret outbound to Google/GitHub
 * on every token exchange, the opposite shape from a machine credential Clavaris only ever verifies
 * against a presented value — same distinction {@code WebhookSigningSecretCipher}'s own Javadoc
 * (webhook-module) already states for an identical need.
 */
@SuppressWarnings("PMD.LongVariable") // encryptedClientSecret names exactly what it is — a
// shortened identifier would only make the one call site (SetOrganizationSocialCredentialService)
// harder to read.
public interface OrganizationSocialCredentialCipher {

  String encrypt(String rawClientSecret);

  String decrypt(String encryptedClientSecret);
}

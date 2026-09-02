package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

/**
 * Outbound port — {@code WebhookEndpoint}'s own Javadoc explains why this must be reversible
 * encryption, not a one-way hash like {@code OAuthClient.clientSecretHash}. Implemented in
 * infrastructure by an AES-256-GCM adapter keyed from an environment variable, same "one trust root
 * that doesn't derive from anything else, seeded from the environment, never via an HTTP endpoint"
 * posture the {@code PlatformClient} bootstrap credential already establishes — a compromise of
 * this key would expose every Organization's every webhook signing secret at once, so it's treated
 * with the same care.
 */
public interface WebhookSigningSecretCipher {

  String encrypt(String rawSecret);

  String decrypt(String encryptedSecret);
}

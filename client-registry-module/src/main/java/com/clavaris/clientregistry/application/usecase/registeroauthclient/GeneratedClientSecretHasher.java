package com.clavaris.clientregistry.application.usecase.registeroauthclient;

/**
 * Outbound port for hashing a client secret that the server itself generated with at least 256 bits
 * of entropy (the {@code OAuthClientSecretGenerator} family). Deliberately a different port from
 * {@code ClientSecretHasher}, so which kind of secret reaches which hash is visible in each use
 * case's constructor: only a secret nobody chose may take the cheap path; the platform client's
 * operator-chosen bootstrap secret keeps {@code ClientSecretHasher} (Argon2id). See {@code
 * HighEntropyClientSecret} for why the two differ.
 */
@FunctionalInterface
public interface GeneratedClientSecretHasher {

  String hash(String rawSecret);
}

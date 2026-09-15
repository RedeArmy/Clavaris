package com.clavaris.identity.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.activateplatformsigningkey.PlatformSigningKeyRepository;
import com.clavaris.identity.domain.model.PlatformSigningKey;
import java.security.KeyPair;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Holds the platform issuer's own RSA key pair (ADR-0002: RS256).
 *
 * <p>TD-SEC-002 (closed): on construction, checks for an already-persisted active key first (a
 * durable restart) before generating a fresh one — a process restart used to generate a new key
 * pair unconditionally, invalidating every previously-issued platform token (a routine deploy
 * acting like a mass logout).
 *
 * <p>Exposes only {@code java.security} types, not a Nimbus {@code JWKSource} — building the real
 * {@code JWKSource<SecurityContext>} is protocol wiring that belongs in {@code app}'s own config,
 * not something identity-module needs spring-security-oauth2-authorization-server for.
 *
 * <p>TD-SEC-054 (closed): key material now lives in its own {@link KeyStoreScope#platform} file,
 * separate from every Organization's own. PMD suppressions below: coding-standards.md §3a.
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
@Component
public class PlatformSigningKeyMaterial {

  private final KeyPair keyPair;
  private final String kid;

  /* package */ PlatformSigningKeyMaterial(
      final PlatformSigningKeyRepository repository, final SigningKeyStore keyStore) {
    final KeyStoreScope scope = KeyStoreScope.platform();
    final Optional<PlatformSigningKey> active = repository.findActive();
    final Optional<KeyPair> persisted = active.flatMap(key -> keyStore.find(scope, key.kid()));

    if (active.isPresent() && persisted.isPresent()) {
      this.kid = active.get().kid();
      this.keyPair = persisted.get();
    } else {
      this.kid = UUID.randomUUID().toString();
      this.keyPair = keyStore.generate(scope, this.kid);
    }
  }

  public KeyPair keyPair() {
    return keyPair;
  }

  public String kid() {
    return kid;
  }
}

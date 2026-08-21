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
 * <p>TD-SEC-002 (closed): on construction, this bean first checks whether {@code
 * platform_signing_keys} already has an active row <em>and</em> {@link SigningKeyStore} already has
 * matching material for that row's {@code kid} — the durable-restart case, where a previous process
 * already generated and persisted a key. Only when that lookup comes up empty (the true first-ever
 * boot, or a compromised key's row/keystore entry was deliberately removed — see {@code
 * incident-response-signing-key-compromise.md} §3) is a brand-new key pair generated. Every process
 * restart used to generate a fresh key pair unconditionally, invalidating every previously-issued
 * platform token; a routine deploy is no longer indistinguishable from a mass logout.
 *
 * <p>This class deliberately exposes only {@code java.security} types, not a Nimbus {@code
 * JWKSource} — building the actual {@code JWKSource<SecurityContext>} Spring Authorization Server's
 * filters read (spike §5.3, Appendix B) is protocol wiring that belongs in {@code app}'s own
 * infrastructure config, alongside the rest of the platform issuer's {@code SecurityFilterChain} —
 * not something identity-module itself needs to depend on
 * spring-security-oauth2-authorization-server to produce.
 *
 * <p>PMD's AvoidFieldNameMatchingMethodName rule flags {@code keyPair}/{@code kid} for the same
 * reason {@code Account} suppresses it — the deliberate record-style accessor convention used
 * throughout this codebase's value objects.
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
@Component
public class PlatformSigningKeyMaterial {

  private final KeyPair keyPair;
  private final String kid;

  /* package */ PlatformSigningKeyMaterial(
      final PlatformSigningKeyRepository repository, final SigningKeyStore keyStore) {
    final Optional<PlatformSigningKey> active = repository.findActive();
    final Optional<KeyPair> persisted = active.flatMap(key -> keyStore.find(key.kid()));

    if (active.isPresent() && persisted.isPresent()) {
      this.kid = active.get().kid();
      this.keyPair = persisted.get();
    } else {
      this.kid = UUID.randomUUID().toString();
      this.keyPair = keyStore.generate(this.kid);
    }
  }

  public KeyPair keyPair() {
    return keyPair;
  }

  public String kid() {
    return kid;
  }
}

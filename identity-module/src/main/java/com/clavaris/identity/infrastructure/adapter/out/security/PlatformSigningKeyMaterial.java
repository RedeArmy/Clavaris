package com.clavaris.identity.infrastructure.adapter.out.security;

import java.security.KeyPair;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Holds the platform issuer's own RSA key pair (ADR-0002: RS256) — generated once, in memory, when
 * this bean is constructed at application startup.
 *
 * <p><b>Known, deliberate limitation, not a silent gap:</b> the actual key material is never
 * persisted (spike follow-up item #2, {@code
 * docs/03-architecture/spikes/0001-spring-authorization-server-multitenancy.md} §8 — {@code
 * TOKEN_SIGNING_KEY_STORE_PATH}-backed persistence is explicitly out of scope for this slice).
 * Every process restart generates a brand-new key pair, invalidating every previously-issued
 * platform token — acceptable for now because the platform issuer only serves the low-volume,
 * operator-only management API, not any end-user-facing flow, but a real limitation that must be
 * closed before this is relied on for anything higher-stakes.
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

  /* package */ PlatformSigningKeyMaterial() {
    this.keyPair = RsaKeyPairs.generate();
    this.kid = UUID.randomUUID().toString();
  }

  public KeyPair keyPair() {
    return keyPair;
  }

  public String kid() {
    return kid;
  }
}

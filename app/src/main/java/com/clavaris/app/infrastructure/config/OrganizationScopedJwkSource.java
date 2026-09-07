package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.infrastructure.adapter.out.security.ActiveSigningKey;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The dynamic half of the spike's Appendix B pattern (spike 0001's Appendix C addendum,
 * docs/03-architecture/spikes/0001-spring-authorization-server-multitenancy.md): a single instance
 * of this class is shared across every Organization's requests on the one dynamic {@code
 * SecurityFilterChain} ({@link OrganizationAuthorizationServerConfig}) — unlike the platform tier's
 * {@code PlatformSigningKeyMaterial}-backed JWKSource, which is fixed for the process lifetime,
 * {@link #get(JWKSelector, SecurityContext)} resolves the current tenant on every call via {@link
 * CurrentOrganizationContext}, never from anything fixed at construction time.
 */
final class OrganizationScopedJwkSource implements JWKSource<SecurityContext> {

  private final OrganizationSigningKeyMaterialFactory keyMaterial;

  /* package */ OrganizationScopedJwkSource(
      final OrganizationSigningKeyMaterialFactory keyMaterial) {
    this.keyMaterial = keyMaterial;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public List<JWK> get(final JWKSelector jwkSelector, final SecurityContext context) {
    final Optional<UUID> organizationId = CurrentOrganizationContext.currentOrganizationId();
    if (organizationId.isEmpty()) {
      return List.of();
    }
    final Optional<RSAKey> rsaKey = activeRsaKeyFor(new OrganizationId(organizationId.get()));
    return rsaKey.map(key -> jwkSelector.select(new JWKSet(key))).orElseGet(List::of);
  }

  // TD-PERF-014: a single activeSigningKeyFor call, not the two separate lookups
  // (SigningKeyRepository.findActive for the kid, keyMaterial.keyPairFor for the KeyPair) this used
  // to make — the first was a real, avoidable Postgres round trip on every token issuance, since
  // OrganizationSigningKeyMaterialFactory's own cache already knows the kid at the exact moment it
  // caches the KeyPair. See that class's own Javadoc.
  private Optional<RSAKey> activeRsaKeyFor(final OrganizationId organizationId) {
    return keyMaterial
        .activeSigningKeyFor(organizationId)
        // BR-ORG-06 provisions a key synchronously at CreateOrganization time, so an empty result
        // should not happen for a real, fully-created Organization — but a JWKS/token request is
        // exactly the wrong place to throw a raw exception over it; an empty key set fails
        // signature verification/signing cleanly at the SAS layer instead of leaking a stack trace.
        .map(OrganizationScopedJwkSource::toRsaKey);
  }

  private static RSAKey toRsaKey(final ActiveSigningKey activeSigningKey) {
    return new RSAKey.Builder((RSAPublicKey) activeSigningKey.keyPair().getPublic())
        .privateKey((RSAPrivateKey) activeSigningKey.keyPair().getPrivate())
        .keyID(activeSigningKey.kid())
        .build();
  }
}

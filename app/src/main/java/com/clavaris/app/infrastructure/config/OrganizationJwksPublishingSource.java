package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TD-SEC-008/ADR-0010 §5.2: the JWKS-<em>publishing</em> half of key rotation with real overlap — a
 * deliberately separate {@link JWKSource} instance from {@link OrganizationScopedJwkSource}, even
 * though both are wired into the same {@code SecurityFilterChain} and read the same underlying
 * signing-key state.
 *
 * <p>The split exists because {@code NimbusJwtEncoder} and {@code NimbusJwkSetEndpointFilter} have
 * fundamentally different, incompatible requirements for how many keys {@link #get} may return —
 * confirmed by decompiling the resolved {@code spring-security-oauth2-jose} jar, not assumed:
 * {@code NimbusJwtEncoder.selectJwk} throws {@code JwtEncodingException} outright if its own {@code
 * JWKSource} ever returns more than one matching key (an ambiguous "which key do I sign with" is
 * treated as a hard error, correctly). The JWKS endpoint filter has the opposite requirement — it
 * must publish every key a verifier might still need, active or recently retired alike, or a
 * still-valid pre-rotation token becomes unverifiable the moment its {@code kid} disappears from
 * {@code /oauth2/jwks}. One {@code JWKSource} cannot satisfy both contracts at once, so {@link
 * OrganizationAuthorizationServerConfig} constructs two: {@code OrganizationScopedJwkSource}
 * (unchanged, always exactly the current active key) feeds the {@code NimbusJwtEncoder} that signs
 * new tokens; this class is what {@code http.setSharedObject(JWKSource.class, ...)} registers
 * instead, which is what {@code NimbusJwkSetEndpointFilter} actually resolves and serializes into
 * the wire response.
 */
final class OrganizationJwksPublishingSource implements JWKSource<SecurityContext> {

  private final SigningKeyRepository signingKeys;
  private final OrganizationSigningKeyMaterialFactory keyMaterial;
  private final Duration overlapWindow;

  /* package */ OrganizationJwksPublishingSource(
      final SigningKeyRepository signingKeys,
      final OrganizationSigningKeyMaterialFactory keyMaterial,
      final Duration overlapWindow) {
    this.signingKeys = signingKeys;
    this.keyMaterial = keyMaterial;
    this.overlapWindow = overlapWindow;
  }

  // "No current Organization on this request"/"resolved" are two independent, equally valid
  // exits — same rationale as OrganizationScopedJwkSource's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public List<JWK> get(final JWKSelector jwkSelector, final SecurityContext context) {
    final Optional<UUID> organizationId = CurrentOrganizationContext.currentOrganizationId();
    if (organizationId.isEmpty()) {
      return List.of();
    }
    final List<JWK> keys = jwksFor(new OrganizationId(organizationId.get()));
    return jwkSelector.select(new JWKSet(keys));
  }

  private List<JWK> jwksFor(final OrganizationId organizationId) {
    final Instant retiredAfter = Instant.now().minus(overlapWindow);
    return signingKeys.findActiveAndRetiredSince(organizationId, retiredAfter).stream()
        .map(this::toRsaKey)
        .flatMap(Optional::stream)
        .toList();
  }

  private Optional<JWK> toRsaKey(final SigningKey key) {
    // A metadata row with no matching keystore entry should never happen (SigningKeyStore never
    // deletes anything this process ever wrote) — skip it rather than fail the whole JWKS response
    // over one anomalous row, same defensive posture OrganizationScopedJwkSource's own equivalent
    // lookup already takes.
    return keyMaterial
        .keyPairForKid(key.kid())
        .map(
            pair ->
                new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey(privateKeyOf(pair))
                    .keyID(key.kid())
                    .build());
  }

  private static RSAPrivateKey privateKeyOf(final KeyPair pair) {
    return (RSAPrivateKey) pair.getPrivate();
  }
}

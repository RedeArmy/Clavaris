package com.clavaris.identity.infrastructure.adapter.out.security;

import com.clavaris.identity.domain.model.OrganizationId;
import java.security.KeyPair;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * BR-ORG-04/BR-ORG-06: generates a fresh RSA key pair for a newly-created Organization, on demand —
 * unlike {@link PlatformSigningKeyMaterial}, which generates exactly once at startup, there is no
 * fixed number of Organizations known ahead of time, so this factory is called once per {@code
 * CreateOrganization} invocation instead.
 *
 * <p><b>Known, deliberate limitations, not silent gaps:</b>
 *
 * <ul>
 *   <li>Same as the platform tier (spike follow-up item #2): key material is held in memory only,
 *       keyed by {@link OrganizationId}, and lost on restart — every Organization's tokens issued
 *       before a restart stop verifying after one, until a fresh key is generated.
 *   <li>This map holds at most one key pair per Organization at a time — a later call for the same
 *       {@link OrganizationId} (manual rotation, ADR-0010 §5.2) overwrites the previous entry
 *       immediately. The overlap requirement ("JWKS always exposes the previous key until every
 *       issued token under it has expired") is NOT yet satisfied by this class alone; real rotation
 *       support needs to retain retired keys until expiry, not just the metadata row {@code
 *       SigningKey.retire()} already tracks. Acceptable for {@code CreateOrganization} (a brand-new
 *       Organization has no previous key to overlap with) but must be closed before the rotation
 *       endpoint (`api-contract-overview.md` §3) goes live.
 *   <li>Deliberately does NOT wire a per-Organization {@code SecurityFilterChain}/JWKS endpoint —
 *       that's the spike's Appendix A discovery-filter pattern, a separate slice ("don't build
 *       ahead of the use case that needs it"). This class only makes the key material exist and be
 *       retrievable by {@link OrganizationId}; wiring it into a real per-tenant OIDC issuer is the
 *       next slice after {@code CreateOrganization}, not part of it.
 * </ul>
 */
@Component
public class OrganizationSigningKeyMaterialFactory {

  private final Map<UUID, KeyPair> keyPairs = new ConcurrentHashMap<>();

  // Constructed only by Spring's own component scan (via @Component above) — never directly by
  // other code.
  /* package */ OrganizationSigningKeyMaterialFactory() {
    // Intentionally empty — this class holds no injected state, only the map above.
  }

  /**
   * Generates and stores a brand-new key pair for {@code organizationId}, returning its {@code
   * kid}.
   */
  public String generateFor(final OrganizationId organizationId) {
    final KeyPair keyPair = RsaKeyPairs.generate();
    final String kid = UUID.randomUUID().toString();
    keyPairs.put(organizationId.value(), keyPair);
    return kid;
  }

  public Optional<KeyPair> keyPairFor(final OrganizationId organizationId) {
    return Optional.ofNullable(keyPairs.get(organizationId.value()));
  }
}

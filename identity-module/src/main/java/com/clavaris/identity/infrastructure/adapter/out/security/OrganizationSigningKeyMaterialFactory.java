package com.clavaris.identity.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.domain.model.OrganizationId;
import java.security.KeyPair;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * BR-ORG-04/BR-ORG-06: generates a fresh RSA key pair for a newly-created Organization, on demand —
 * unlike {@link PlatformSigningKeyMaterial}, which resolves its one key eagerly at construction,
 * there is no fixed number of Organizations known ahead of time, so this factory is called once per
 * {@code CreateOrganization} invocation instead, and every other lookup is served from an in-memory
 * cache keyed by {@link OrganizationId} first.
 *
 * <p>TD-SEC-002 (closed): a cache miss no longer means "never generated" — it may just mean this
 * process restarted and lost the in-memory map, while the durable state ({@code signing_keys}'
 * active row, plus the matching {@link SigningKeyStore} entry) survived. {@link
 * #keyPairFor(OrganizationId)} now falls back to reloading from both before giving up, so a restart
 * no longer invalidates every Organization's previously-issued tokens the moment their key is next
 * looked up.
 *
 * <p><b>Known, deliberate limitations, not silent gaps:</b>
 *
 * <ul>
 *   <li>This map/store holds at most one key pair per Organization at a time — a later call for the
 *       same {@link OrganizationId} (manual rotation, ADR-0010 §5.2) overwrites the previous entry
 *       immediately. The overlap requirement ("JWKS always exposes the previous key until every
 *       issued token under it has expired") is NOT yet satisfied by this class alone; real rotation
 *       support needs to retain retired keys until expiry, not just the metadata row {@code
 *       SigningKey.retire()} already tracks. Acceptable for {@code CreateOrganization} (a brand-new
 *       Organization has no previous key to overlap with) but must be closed before the rotation
 *       endpoint (`api-contract-overview.md` §3) goes live — tracked as TD-SEC-008.
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
  private final SigningKeyRepository signingKeys;
  private final SigningKeyStore keyStore;

  /* package */ OrganizationSigningKeyMaterialFactory(
      final SigningKeyRepository signingKeys, final SigningKeyStore keyStore) {
    this.signingKeys = signingKeys;
    this.keyStore = keyStore;
  }

  /**
   * Generates and stores a brand-new key pair for {@code organizationId}, returning its {@code
   * kid}.
   */
  public String generateFor(final OrganizationId organizationId) {
    final String kid = UUID.randomUUID().toString();
    final KeyPair keyPair = keyStore.generate(kid);
    keyPairs.put(organizationId.value(), keyPair);
    return kid;
  }

  @SuppressWarnings("PMD.OnlyOneReturn") // early-return cache-hit path reads clearer than nesting
  public Optional<KeyPair> keyPairFor(final OrganizationId organizationId) {
    final KeyPair cached = keyPairs.get(organizationId.value());
    if (cached != null) {
      return Optional.of(cached);
    }
    return reloadFromPersistentStore(organizationId);
  }

  // TD-SEC-002: the durable-restart path — the metadata row and the keystore entry both outlive
  // this bean's own in-memory cache, so a cache miss reloads from them instead of assuming the key
  // was simply never generated.
  private Optional<KeyPair> reloadFromPersistentStore(final OrganizationId organizationId) {
    return signingKeys
        .findActive(organizationId)
        .flatMap(activeKey -> keyStore.find(activeKey.kid()))
        .map(
            reloaded -> {
              keyPairs.put(organizationId.value(), reloaded);
              return reloaded;
            });
  }
}

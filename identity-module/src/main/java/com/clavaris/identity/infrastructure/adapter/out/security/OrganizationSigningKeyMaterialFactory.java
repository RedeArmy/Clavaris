package com.clavaris.identity.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.SigningKeyMaterialGenerator;
import com.clavaris.identity.domain.model.OrganizationId;
import java.security.KeyPair;
import java.util.Collection;
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
 * <p>TD-SEC-051 (closed): {@link #generateFor} used to write straight into this cache — see {@link
 * SigningKeyMaterialGenerator}'s own Javadoc for the real, live-traced race that let two concurrent
 * rotations leave this cache holding a different key than the one the DB's own advisory lock had
 * just activated. {@link #generateFor} now only mints and persists key material; {@link
 * #cacheActive} is the only method that ever writes to this cache, and every real caller only
 * invokes it after the DB-level activation it's racing against has already committed.
 *
 * <p><b>Known, deliberate limitations, not silent gaps:</b>
 *
 * <ul>
 *   <li>This map/store holds at most one key pair per Organization at a time — a later call for the
 *       same {@link OrganizationId} (manual rotation, ADR-0010 §5.2) overwrites the previous entry
 *       immediately. That's fine for signing new tokens (which must always use the current key,
 *       never a retired one), but JWKS <em>publishing</em> needs the overlap this cache alone can't
 *       give it — closed (TD-SEC-008) not by changing this cache's shape, but by {@link
 *       #keyPairForKid(OrganizationId, String)} bypassing it entirely: {@link SigningKeyStore}
 *       already retains every key it ever wrote, so a retired kid's material is still reachable
 *       directly, and {@code OrganizationJwksPublishingSource} (app module) is what actually looks
 *       up every still-in-window kid this way for the JWKS response.
 *   <li>Deliberately does NOT wire a per-Organization {@code SecurityFilterChain}/JWKS endpoint —
 *       that's the spike's Appendix A discovery-filter pattern, a separate slice ("don't build
 *       ahead of the use case that needs it"). This class only makes the key material exist and be
 *       retrievable by {@link OrganizationId}; wiring it into a real per-tenant OIDC issuer is the
 *       next slice after {@code CreateOrganization}, not part of it.
 * </ul>
 *
 * <p>TD-SEC-052 (closed): {@link #purgeAllFor} exists specifically because everything above this
 * paragraph used to be a one-way door — every key ever generated stayed in {@link SigningKeyStore}
 * forever, and a deleted Organization's own cache entry (and every historical PKCS12 entry {@code
 * keyStore} still held for it) simply outlived the Organization, with no eviction path at all.
 * {@code OrganizationIdentityDataEraserBridge} (app module) now calls it as the last step of
 * Organization hard-deletion, the same top-severity treatment TD-SEC-029's emergency purge already
 * gives a single compromised key, applied here to every key an entire deleted tenant ever had.
 *
 * <p>TD-PERF-014 (closed): the cache used to hold a bare {@link KeyPair}, forcing {@code
 * OrganizationScopedJwkSource} to run its own separate {@code SigningKeyRepository.findActive}
 * Postgres query on every single token issuance purely to learn the active {@code kid} — a real DB
 * round trip this cache could already answer, since {@link #cacheActive}/{@link
 * #reloadFromPersistentStore} both already know the {@code kid} at the exact moment they populate
 * this map, and simply threw it away. The cache now holds {@link ActiveSigningKey} (kid + KeyPair
 * together); {@link #activeSigningKeyFor} is the new method that serves both from one lookup,
 * {@link #keyPairFor} stays for the one other caller ({@code
 * OrganizationSigningKeyPublicKeyProviderBridge}) that only ever needed the {@link KeyPair}.
 *
 * <p>TD-SEC-054 (closed): every call into {@link SigningKeyStore} now passes a {@link
 * KeyStoreScope} derived from {@code organizationId} — see that class's own Javadoc for why the
 * previous single-shared-file design meant a signing-key compromise at the storage layer was never
 * actually single-tenant, only the application-layer routing was.
 */
@Component
public class OrganizationSigningKeyMaterialFactory implements SigningKeyMaterialGenerator {

  private final Map<UUID, ActiveSigningKey> activeSigningKeys = new ConcurrentHashMap<>();
  private final SigningKeyRepository signingKeys;
  private final SigningKeyStore keyStore;

  /* package */ OrganizationSigningKeyMaterialFactory(
      final SigningKeyRepository signingKeys, final SigningKeyStore keyStore) {
    this.signingKeys = signingKeys;
    this.keyStore = keyStore;
  }

  /**
   * Generates and persists a brand-new key pair for {@code organizationId} to the key store,
   * returning its {@code kid} — see {@link SigningKeyMaterialGenerator#generateFor}'s own Javadoc
   * (TD-SEC-051) for why this deliberately does NOT also cache it as the active key.
   */
  @Override
  public String generateFor(final OrganizationId organizationId) {
    final String kid = UUID.randomUUID().toString();
    keyStore.generate(KeyStoreScope.organization(organizationId.value()), kid);
    return kid;
  }

  /**
   * TD-SEC-051: see {@link SigningKeyMaterialGenerator#cacheActive}'s own Javadoc for the full
   * ordering contract every caller must follow. Reloads from {@link SigningKeyStore} rather than
   * threading the {@link KeyPair} {@link #generateFor} already computed through as a return value —
   * a deliberately simple, already-synchronized read path (the same one {@link #keyPairForKid}
   * already uses), not a second cache/holder structure with its own cleanup-on-failure concerns.
   */
  @Override
  public void cacheActive(final OrganizationId organizationId, final String kid) {
    final KeyPair keyPair =
        keyStore
            .find(KeyStoreScope.organization(organizationId.value()), kid)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Signing key '"
                            + kid
                            + "' was just generated/activated for Organization "
                            + organizationId.value()
                            + " but has no matching key store entry — data integrity violated"
                            + " before reaching this call"));
    activeSigningKeys.put(organizationId.value(), new ActiveSigningKey(kid, keyPair));
  }

  /**
   * TD-SEC-052: irreversibly removes every trace of {@code organizationId}'s own signing-key
   * material this process can reach — the in-memory cache entry (if any is currently held for this
   * Organization) plus every one of {@code kids}'s own key-store entries. Deliberately takes the
   * full {@code kid} list as a parameter rather than looking it up itself: by the time an
   * Organization is actually being purged, its own {@code signing_keys} rows are typically already
   * gone (the caller must read the full history <em>before</em> deleting those rows, the same "read
   * before you can no longer look it up" ordering {@code OrganizationIdentityDataEraserBridge}'s
   * own class Javadoc already documents for revoking every live session before the account rows are
   * bulk-deleted). Safe to call with an empty or already-purged {@code kids} collection — {@link
   * SigningKeyStore#delete} is a no-op for a {@code kid} it never had, or already removed.
   */
  public void purgeAllFor(final OrganizationId organizationId, final Collection<String> kids) {
    activeSigningKeys.remove(organizationId.value());
    final KeyStoreScope scope = KeyStoreScope.organization(organizationId.value());
    for (final String kid : kids) {
      keyStore.delete(scope, kid);
    }
  }

  /**
   * TD-SEC-008: looks up key material by {@code kid} directly, bypassing the per-Organization
   * "current active key" cache entirely — the only way to reach a retired-but-still-in-the-JWKS-
   * overlap-window key's material, since {@link #keyPairFor(OrganizationId)}'s cache holds at most
   * one entry per Organization (the currently active one) by design. {@link SigningKeyStore} never
   * deletes a key once written, so this works for any {@code kid} this process ever generated,
   * active or retired alike. TD-SEC-054: {@code organizationId} is now required — {@link
   * SigningKeyStore} is scoped per Organization, not one shared store, so knowing which
   * Organization's file to look in is no longer optional.
   */
  public Optional<KeyPair> keyPairForKid(final OrganizationId organizationId, final String kid) {
    return keyStore.find(KeyStoreScope.organization(organizationId.value()), kid);
  }

  /**
   * The one other caller of this cache ({@code OrganizationSigningKeyPublicKeyProviderBridge}) only
   * ever needs the {@link KeyPair} itself, never the {@code kid} — this stays a thin projection of
   * {@link #activeSigningKeyFor} rather than its own separate cache read.
   */
  public Optional<KeyPair> keyPairFor(final OrganizationId organizationId) {
    return activeSigningKeyFor(organizationId).map(ActiveSigningKey::keyPair);
  }

  /**
   * TD-PERF-014: the active {@code kid} and its {@link KeyPair} together, from one cache lookup —
   * see this class's own Javadoc for why {@code OrganizationScopedJwkSource} needed this instead of
   * its own separate {@code SigningKeyRepository.findActive} query on every token issuance.
   */
  @SuppressWarnings("PMD.OnlyOneReturn") // early-return cache-hit path reads clearer than nesting
  public Optional<ActiveSigningKey> activeSigningKeyFor(final OrganizationId organizationId) {
    final ActiveSigningKey cached = activeSigningKeys.get(organizationId.value());
    if (cached != null) {
      return Optional.of(cached);
    }
    return reloadFromPersistentStore(organizationId);
  }

  // TD-SEC-002: the durable-restart path — the metadata row and the keystore entry both outlive
  // this bean's own in-memory cache, so a cache miss reloads from them instead of assuming the key
  // was simply never generated.
  private Optional<ActiveSigningKey> reloadFromPersistentStore(
      final OrganizationId organizationId) {
    final KeyStoreScope scope = KeyStoreScope.organization(organizationId.value());
    return signingKeys
        .findActive(organizationId)
        .flatMap(
            activeKey ->
                keyStore
                    .find(scope, activeKey.kid())
                    .map(pair -> new ActiveSigningKey(activeKey.kid(), pair)))
        .map(
            reloaded -> {
              activeSigningKeys.put(organizationId.value(), reloaded);
              return reloaded;
            });
  }
}

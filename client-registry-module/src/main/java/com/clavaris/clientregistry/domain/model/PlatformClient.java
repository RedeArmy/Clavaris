package com.clavaris.clientregistry.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0010 (Organization provisioning), BR-PLATFORM-01/02/03: authenticates the entire {@code
 * /api/v1/admin/*} management-API surface, including {@code POST /api/v1/admin/organizations}
 * itself — the one call that, by definition, can't be authenticated by a token belonging to the
 * Organization it's about to create. Deliberately a separate class from {@code OAuthClient}
 * (client-registry-module's tenant-scoped registrations, not yet implemented) — belongs to no
 * Organization at all, not a nullable-{@code organizationId} row on the same table (data-model.md
 * §2).
 *
 * <p>PMD's AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this class for
 * the same reason identity-module's {@code Account} suppresses them — the deliberate record-style
 * accessor convention used throughout this codebase's value objects, not an accidental data-holder
 * shape. DataClass itself is no longer flagged now that {@link #rotateSecret(String)}/{@link
 * #deactivate()} give this class real behavior beyond plain accessors. TooManyMethods: six one-line
 * accessors, two rehydration factories, and the TD-ARCH-004 scope validator is what a value object
 * with this many fields and one real invariant looks like, not organic growth — same reasoning
 * {@code OAuthClient}'s own identical suppression documents.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods"
})
public final class PlatformClient {

  private final UUID id;
  private final String clientId;
  private final String clientSecretHash;
  private final List<String> allowedScopes;
  private final Instant createdAt;
  private final boolean active;

  private PlatformClient(
      final UUID id,
      final String clientId,
      final String clientSecretHash,
      final List<String> allowedScopes,
      final Instant createdAt,
      final boolean active) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
    this.clientSecretHash =
        Objects.requireNonNull(clientSecretHash, "clientSecretHash must not be null");
    this.allowedScopes = requireValidScopes(allowedScopes);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.active = active;
    if (clientId.isBlank()) {
      throw new IllegalArgumentException("clientId must not be blank");
    }
    if (clientSecretHash.isBlank()) {
      // Same defensive rationale as PasswordCredential's own blank-hash guard: a hasher bug
      // producing an empty hash must fail loudly here, not silently reach persistence as a
      // credential nothing (and everything) authenticates against — for THIS credential
      // specifically, the highest-value target in the whole system.
      throw new IllegalArgumentException("clientSecretHash must not be blank");
    }
  }

  /**
   * @param clientSecretHash the already-hashed value — this factory never sees or accepts a raw
   *     secret; hashing happens at the port boundary, same discipline as {@code
   *     PasswordCredential#issue}.
   */
  public static PlatformClient register(
      final String clientId, final String clientSecretHash, final List<String> allowedScopes) {
    return new PlatformClient(
        UUID.randomUUID(), clientId, clientSecretHash, allowedScopes, Instant.now(), true);
  }

  /**
   * Rehydrates an existing row read back from persistence — deliberately a separate factory from
   * {@link #register}, which means "a brand new registration event" and always mints a fresh {@code
   * id}/{@code createdAt}. Needed because, unlike {@code identity-module}'s write-only-so-far
   * {@code Account}, a {@code PlatformClient} is read back at token-request time by Spring
   * Authorization Server's {@code RegisteredClientRepository} adapter (app module) — losing the
   * real persisted {@code id} here (e.g. by calling {@link #register} again) would be exactly the
   * class of bug already caught once in {@code JpaAccountRepository}'s own history.
   */
  public static PlatformClient reconstitute(
      final UUID id,
      final String clientId,
      final String clientSecretHash,
      final List<String> allowedScopes,
      final Instant createdAt,
      final boolean active) {
    return new PlatformClient(id, clientId, clientSecretHash, allowedScopes, createdAt, active);
  }

  /**
   * TD-SEC-018: replaces the credential in place — same id/clientId/allowedScopes/createdAt, a
   * fresh hash. The only way to rotate this credential today that isn't raw SQL against production.
   * {@code newClientSecretHash} is already hashed, same "never see a raw secret" discipline as
   * {@link #register}.
   */
  public PlatformClient rotateSecret(
      @SuppressWarnings("PMD.LongVariable") final String newClientSecretHash) {
    return new PlatformClient(id, clientId, newClientSecretHash, allowedScopes, createdAt, active);
  }

  /**
   * TD-SEC-018: an inactive {@code PlatformClient} must never authenticate a new {@code
   * client_credentials} exchange again — {@code PlatformRegisteredClientRepository} (app module) is
   * what actually enforces this, treating an inactive client the same as a not-found one.
   * Already-issued tokens are unaffected (bounded by their own short TTL, same residual window
   * {@code incident-response-platform-client-compromise.md} already documents honestly) — this is
   * revocation of the credential's ability to mint new tokens, not a live check on every request.
   */
  public PlatformClient deactivate() {
    return new PlatformClient(id, clientId, clientSecretHash, allowedScopes, createdAt, false);
  }

  public UUID id() {
    return id;
  }

  public String clientId() {
    return clientId;
  }

  public String clientSecretHash() {
    return clientSecretHash;
  }

  public List<String> allowedScopes() {
    return allowedScopes;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public boolean active() {
    return active;
  }

  // TD-ARCH-004: every entry must be a real, known platform:*-namespaced scope
  // (PlatformScopes.BOOTSTRAP_DEFAULT — already the canonical "every scope that exists" list per
  // its own Javadoc, so reused verbatim here rather than a second, driftable list). Before this,
  // allowedScopes was free text nothing validated — a typo'd scope silently granted nothing (a
  // client believing it holds a capability it doesn't), and nothing stopped this class's own
  // instances from being handed a scope from an entirely different vocabulary. An empty list stays
  // valid (a revoked-down-to-nothing or not-yet-provisioned client authenticates but can do
  // nothing, same "existing, intentional empty state" this codebase's own
  // DeactivatePlatformClientServiceTest/RotatePlatformClientSecretServiceTest already exercise) —
  // only unknown scope *values* are rejected, not the absence of any.
  private static List<String> requireValidScopes(final List<String> allowedScopes) {
    Objects.requireNonNull(allowedScopes, "allowedScopes must not be null");
    for (final String scope : allowedScopes) {
      if (!PlatformScopes.BOOTSTRAP_DEFAULT.contains(scope)) {
        throw new IllegalArgumentException("allowedScopes contains an unknown scope: " + scope);
      }
    }
    return List.copyOf(allowedScopes);
  }
}

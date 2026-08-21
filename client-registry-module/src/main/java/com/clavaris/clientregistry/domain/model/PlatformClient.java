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
 * <p>PMD's DataClass/AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this
 * class for the same reason identity-module's {@code Account} suppresses them — the deliberate
 * record-style accessor convention used throughout this codebase's value objects, not an accidental
 * data-holder shape.
 */
@SuppressWarnings({
  "PMD.DataClass",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class PlatformClient {

  private final UUID id;
  private final String clientId;
  private final String clientSecretHash;
  private final List<String> allowedScopes;
  private final Instant createdAt;

  private PlatformClient(
      final UUID id,
      final String clientId,
      final String clientSecretHash,
      final List<String> allowedScopes,
      final Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
    this.clientSecretHash =
        Objects.requireNonNull(clientSecretHash, "clientSecretHash must not be null");
    this.allowedScopes =
        List.copyOf(Objects.requireNonNull(allowedScopes, "allowedScopes must not be null"));
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
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
        UUID.randomUUID(), clientId, clientSecretHash, allowedScopes, Instant.now());
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
      final Instant createdAt) {
    return new PlatformClient(id, clientId, clientSecretHash, allowedScopes, createdAt);
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
}

package com.clavaris.clientregistry.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0023 (per-Organization admin credential, Clerk "Secret Key" parity): the admin-power
 * counterpart to {@link PlatformClient}, but scoped to exactly one {@code Organization} instead of
 * the whole platform — the same {@code client_credentials} authentication shape, the same {@code
 * allowedScopes} vocabulary ({@link PlatformScopes}, reused verbatim, not a parallel scope
 * namespace: the *operations* are the same use cases {@code PlatformClient} already reaches, this
 * credential only narrows *which* Organization they may target). Deliberately a separate class and
 * table from both {@code PlatformClient} (belongs to no Organization) and {@code OAuthClient}
 * (end-user OIDC login, never admin-API power) — three distinct credential shapes for three
 * distinct trust boundaries, not one table with optional columns papering over the difference.
 *
 * <p>{@code organizationId} is a plain {@link UUID}, not identity-module's own {@code
 * OrganizationId} — same module-independence rule {@code OAuthClient}'s own identical field already
 * documents.
 *
 * <p>Same record-style-accessor PMD suppressions as {@link PlatformClient}, same rationale. {@code
 * PMD.TooManyMethods}: a value object whose method count grows with its field count, not organic
 * complexity — same reasoning {@code Organization}'s own identical suppression documents.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods"
})
public final class OrganizationClient {

  private final UUID id;
  private final UUID organizationId;
  private final String clientId;
  private final String clientSecretHash;
  private final List<String> allowedScopes;
  private final Instant createdAt;
  private final boolean active;

  @SuppressWarnings("java:S107") // one parameter per persisted column, same rationale as
  // PlatformClient's own identical constructor.
  private OrganizationClient(
      final UUID id,
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final List<String> allowedScopes,
      final Instant createdAt,
      final boolean active) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
    this.clientSecretHash =
        Objects.requireNonNull(clientSecretHash, "clientSecretHash must not be null");
    this.allowedScopes =
        List.copyOf(Objects.requireNonNull(allowedScopes, "allowedScopes must not be null"));
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.active = active;
    if (clientId.isBlank()) {
      throw new IllegalArgumentException("clientId must not be blank");
    }
    if (clientSecretHash.isBlank()) {
      // Same defensive rationale as PlatformClient's own identical guard — this credential grants
      // real admin power over one Organization's own accounts/workspaces, a high-value target even
      // if not the system-wide one PlatformClient is.
      throw new IllegalArgumentException("clientSecretHash must not be blank");
    }
  }

  /**
   * @param clientSecretHash the already-hashed value — this factory never sees or accepts a raw
   *     secret, same discipline as {@code PlatformClient#register}.
   */
  public static OrganizationClient register(
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final List<String> allowedScopes) {
    return new OrganizationClient(
        UUID.randomUUID(),
        organizationId,
        clientId,
        clientSecretHash,
        allowedScopes,
        Instant.now(),
        true);
  }

  /** Rehydrates an existing row — same rationale as {@code PlatformClient#reconstitute}. */
  public static OrganizationClient reconstitute(
      final UUID id,
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final List<String> allowedScopes,
      final Instant createdAt,
      final boolean active) {
    return new OrganizationClient(
        id, organizationId, clientId, clientSecretHash, allowedScopes, createdAt, active);
  }

  /** Same rationale as {@code PlatformClient#rotateSecret}. */
  public OrganizationClient rotateSecret(
      @SuppressWarnings("PMD.LongVariable") final String newClientSecretHash) {
    return new OrganizationClient(
        id, organizationId, clientId, newClientSecretHash, allowedScopes, createdAt, active);
  }

  /** Same rationale as {@code PlatformClient#deactivate}. */
  public OrganizationClient deactivate() {
    return new OrganizationClient(
        id, organizationId, clientId, clientSecretHash, allowedScopes, createdAt, false);
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
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
}

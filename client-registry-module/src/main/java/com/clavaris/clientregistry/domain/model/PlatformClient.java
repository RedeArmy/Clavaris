package com.clavaris.clientregistry.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ADR-0010 (Organization provisioning), BR-PLATFORM-01/02/03: authenticates the entire {@code
 * /api/v1/admin/*} management-API surface, including {@code POST /api/v1/admin/organizations}
 * itself — the one call that, by definition, can't be authenticated by a token belonging to the
 * Organization it's about to create. Deliberately a separate class from {@link OAuthClient} (this
 * same module's tenant-scoped registrations) — belongs to no Organization at all, not a
 * nullable-{@code organizationId} row on the same table (data-model.md §2).
 *
 * <p>Shared state/validation lives on {@link AbstractClientCredential} (SonarCloud duplication
 * review, 2026-09-15) — see its own Javadoc for why this pair shares a base and specifically why
 * this class adds no owning-id field at all. {@code PMD.ShortVariable}: {@code id} names exactly
 * what it is — same convention {@link AbstractClientCredential}'s own identical suppression already
 * documents for this same constructor parameter.
 */
@SuppressWarnings("PMD.ShortVariable")
public final class PlatformClient extends AbstractClientCredential {

  private PlatformClient(
      final UUID id,
      final String clientId,
      final String clientSecretHash,
      final List<String> allowedScopes,
      final Instant createdAt,
      final boolean active,
      final int version) {
    super(id, clientId, clientSecretHash, allowedScopes, createdAt, active, version);
  }

  /**
   * @param clientSecretHash the already-hashed value — this factory never sees or accepts a raw
   *     secret; hashing happens at the port boundary, same discipline as {@code
   *     PasswordCredential#issue}.
   */
  public static PlatformClient register(
      final String clientId, final String clientSecretHash, final List<String> allowedScopes) {
    return new PlatformClient(
        UUID.randomUUID(), clientId, clientSecretHash, allowedScopes, Instant.now(), true, 0);
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
      final boolean active,
      final int version) {
    return new PlatformClient(
        id, clientId, clientSecretHash, allowedScopes, createdAt, active, version);
  }

  /**
   * TD-SEC-018: replaces the credential in place — same id/clientId/allowedScopes/createdAt, a
   * fresh hash. The only way to rotate this credential today that isn't raw SQL against production.
   * {@code newClientSecretHash} is already hashed, same "never see a raw secret" discipline as
   * {@link #register}.
   */
  public PlatformClient rotateSecret(
      @SuppressWarnings("PMD.LongVariable") final String newClientSecretHash) {
    return new PlatformClient(
        id(), clientId(), newClientSecretHash, allowedScopes(), createdAt(), active(), version());
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
    return new PlatformClient(
        id(), clientId(), clientSecretHash(), allowedScopes(), createdAt(), false, version());
  }
}

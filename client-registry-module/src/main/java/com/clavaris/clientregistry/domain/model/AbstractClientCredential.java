package com.clavaris.clientregistry.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared state/validation for {@link PlatformClient}/{@link OrganizationClient} — SonarCloud
 * duplication review, 2026-09-15: every field except {@code organizationId} (which {@link
 * PlatformClient} has none of at all — ADR-0010: the platform tier belongs to no Organization,
 * structurally, not merely an unset field) is provably, permanently identical between the two, the
 * same has-owning-id/has-none split {@code AbstractSigningKey} (identity-module, TD-ARCH-009)
 * already established for an identical shape.
 *
 * <p>Deliberately not generic over an owning-id type, unlike identity-module's {@code
 * AbstractPasswordCredential}/{@code AbstractVerificationToken}/{@code AbstractPendingSocialLink} —
 * see {@code AbstractSigningKey}'s own Javadoc for why that pattern doesn't fit a "one side has an
 * owning id, the other structurally has none" pair. {@link PlatformClient#rotateSecret}/{@link
 * PlatformClient#deactivate} (and {@link OrganizationClient}'s own identical pair) deliberately
 * stay on each subclass, not pulled up here: each returns its own concrete type (a brand-new {@code
 * PlatformClient}/{@code OrganizationClient}), which a shared method here could only express with a
 * self-type generic parameter — real complexity this already-tiny duplication (SonarCloud: 0.3%
 * total New Code) doesn't justify. {@link ClientCredentialFields#requireNonBlank} (same-day
 * extraction) already closed this pair's other real duplicate; this closes the rest.
 *
 * <p>Package-private: only this package's own two subclasses need it.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.AbstractClassWithoutAbstractMethod",
  "PMD.PublicMemberInNonPublicType",
  "PMD.DataClass"
})
abstract class AbstractClientCredential {

  private final UUID id;
  private final String clientId;
  private final String clientSecretHash;
  private final List<String> allowedScopes;
  private final Instant createdAt;
  private final boolean active;
  private final int version;

  @SuppressWarnings("java:S107") // one parameter per persisted column — same rationale as either
  // subclass's own private constructor.
  protected AbstractClientCredential(
      final UUID id,
      final String clientId,
      final String clientSecretHash,
      final List<String> allowedScopes,
      final Instant createdAt,
      final boolean active,
      final int version) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.clientId = ClientCredentialFields.requireNonBlank(clientId, "clientId");
    this.clientSecretHash =
        ClientCredentialFields.requireNonBlank(clientSecretHash, "clientSecretHash");
    this.allowedScopes = PlatformScopes.requireValidScopes(allowedScopes);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.active = active;
    this.version = version;
  }

  public final UUID id() {
    return id;
  }

  public final String clientId() {
    return clientId;
  }

  public final String clientSecretHash() {
    return clientSecretHash;
  }

  public final List<String> allowedScopes() {
    return allowedScopes;
  }

  public final Instant createdAt() {
    return createdAt;
  }

  public final boolean active() {
    return active;
  }

  public final int version() {
    return version;
  }
}

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
 * <p>Shared state/validation lives on {@link AbstractClientCredential} (SonarCloud duplication
 * review, 2026-09-15) — see its own Javadoc for why this pair shares a base; {@code organizationId}
 * is this class's own field, added on top, the one thing that pair doesn't share.
 */
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortVariable"})
public final class OrganizationClient extends AbstractClientCredential {

  private final UUID organizationId;

  @SuppressWarnings("java:S107") // one parameter per persisted column, same rationale as
  // PlatformClient's own identical constructor.
  private OrganizationClient(
      final UUID id,
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final List<String> allowedScopes,
      final Instant createdAt,
      final boolean active,
      final int version) {
    super(id, clientId, clientSecretHash, allowedScopes, createdAt, active, version);
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
  }

  /**
   * @param clientSecretHash the already-hashed value — this factory never sees or accepts a raw
   *     secret, same discipline as {@code PlatformClient#register}.
   *     <p>SDE-III review, 2026-09-15: {@code allowedScopes} is validated via {@link
   *     PlatformScopes#requireValidScopesForOrganizationClient}, not the private constructor's own
   *     generic {@link PlatformScopes#requireValidScopes} — the narrower check, applied here and
   *     only here (never on {@link #reconstitute}, so rehydrating an already-persisted row can
   *     never fail even if a v1.1 policy change later widens what's allowed), so no caller of this
   *     factory — the operator's own REST API or the dashboard's tenant self-service form — can
   *     ever mint a Secret Key holding an operator-only scope. See {@link
   *     PlatformScopes#OPERATOR_ONLY}'s own Javadoc for the regression this closes.
   * @throws IllegalArgumentException if any entry isn't a known scope, or is reserved to {@code
   *     PlatformClient}-only administration
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
        PlatformScopes.requireValidScopesForOrganizationClient(allowedScopes),
        Instant.now(),
        true,
        0);
  }

  /**
   * Rehydrates an existing row — same rationale as {@code PlatformClient#reconstitute}.
   *
   * @param version SDE-III review, 2026-09-15: the row's real persisted optimistic-lock version —
   *     see this class's own {@code version} field Javadoc.
   */
  // One parameter per persisted column, same rationale as the private constructor and as
  // OAuthClient's own identical reconstitute (the version field pushed this one past 7 too).
  // PMD.ExcessiveParameterList isn't part of this project's active ruleset (pmd-ruleset.xml) —
  // suppressing it would itself be flagged by PMD.UnnecessaryWarningSuppression.
  @SuppressWarnings("java:S107")
  public static OrganizationClient reconstitute(
      final UUID id,
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final List<String> allowedScopes,
      final Instant createdAt,
      final boolean active,
      final int version) {
    return new OrganizationClient(
        id, organizationId, clientId, clientSecretHash, allowedScopes, createdAt, active, version);
  }

  /** Same rationale as {@code PlatformClient#rotateSecret}. */
  public OrganizationClient rotateSecret(
      @SuppressWarnings("PMD.LongVariable") final String newClientSecretHash) {
    return new OrganizationClient(
        id(),
        organizationId,
        clientId(),
        newClientSecretHash,
        allowedScopes(),
        createdAt(),
        active(),
        version());
  }

  /** Same rationale as {@code PlatformClient#deactivate}. */
  public OrganizationClient deactivate() {
    return new OrganizationClient(
        id(),
        organizationId,
        clientId(),
        clientSecretHash(),
        allowedScopes(),
        createdAt(),
        false,
        version());
  }

  public UUID organizationId() {
    return organizationId;
  }
}

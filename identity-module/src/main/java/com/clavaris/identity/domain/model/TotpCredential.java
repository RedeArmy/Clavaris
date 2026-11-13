package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An Account's authenticator-app secret (ADR-0040). One per Account. It exists unconfirmed while
 * the person is still adding it to their app, and only a confirmed one is ever asked for at
 * sign-in: an abandoned setup must not lock anyone out.
 *
 * <p>{@code encryptedSecret} is opaque here: the domain never sees the plain secret outside the
 * moment of use, and what encrypts it is an infrastructure concern. {@code lastUsedStep} is the
 * highest time step whose code was accepted; a code is accepted only for a later step, so a code
 * that was seen once (over a shoulder, or in a phishing page) cannot be replayed inside its window.
 */
public final class TotpCredential {

  private final UUID id;
  private final AccountId accountId;
  private final OrganizationId organizationId;
  private final String encryptedSecret;
  private final Instant createdAt;
  private Instant confirmedAt;
  private long lastUsedStep;

  private TotpCredential(
      final UUID id,
      final AccountId accountId,
      final OrganizationId organizationId,
      final String encryptedSecret,
      final Instant confirmedAt,
      final long lastUsedStep,
      final Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.encryptedSecret =
        Objects.requireNonNull(encryptedSecret, "encryptedSecret must not be null");
    this.confirmedAt = confirmedAt;
    this.lastUsedStep = lastUsedStep;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  /** A secret just generated, not yet proven to be in the person's app. */
  public static TotpCredential pending(
      final AccountId accountId,
      final OrganizationId organizationId,
      final String encryptedSecret) {
    return new TotpCredential(
        UUID.randomUUID(), accountId, organizationId, encryptedSecret, null, 0, Instant.now());
  }

  public static TotpCredential reconstitute(
      final UUID id,
      final AccountId accountId,
      final OrganizationId organizationId,
      final String encryptedSecret,
      final Instant confirmedAt,
      final long lastUsedStep,
      final Instant createdAt) {
    return new TotpCredential(
        id, accountId, organizationId, encryptedSecret, confirmedAt, lastUsedStep, createdAt);
  }

  public boolean isConfirmed() {
    return confirmedAt != null;
  }

  /** Whether a code for {@code step} may still be accepted, that is, was not already spent. */
  public boolean accepts(final long step) {
    return step > lastUsedStep;
  }

  /** Marks the code of {@code step} as spent. */
  public void spend(final long step) {
    this.lastUsedStep = Math.max(lastUsedStep, step);
  }

  public void confirm(final Instant now, final long step) {
    this.confirmedAt = now;
    spend(step);
  }

  public UUID id() {
    return id;
  }

  public AccountId accountId() {
    return accountId;
  }

  public OrganizationId organizationId() {
    return organizationId;
  }

  public String encryptedSecret() {
    return encryptedSecret;
  }

  public Instant confirmedAt() {
    return confirmedAt;
  }

  public long lastUsedStep() {
    return lastUsedStep;
  }

  public Instant createdAt() {
    return createdAt;
  }
}

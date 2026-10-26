package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The per-provider settings of an Organization's social connection: whether sign-ins whose email
 * uses a subaddress are refused, and whether the provider always shows its account chooser.
 *
 * <p>Optional, like {@link OrganizationSocialCredential}: no row for an {@code (organizationId,
 * provider)} pair is the normal state, and means the behaviour from before these settings existed
 * (nothing blocked, no forced chooser), see {@link #unset}. Nothing changes for an Organization
 * until its connection is saved from the dashboard.
 */
// Same value-object conventions as OrganizationSocialCredential's own identical suppression.
@SuppressWarnings({"PMD.TooManyMethods"})
public final class SocialConnectionSettings {

  private final UUID id;
  private final UUID organizationId;
  private final SocialProvider provider;
  private final boolean blockEmailSubaddresses;
  private final boolean alwaysShowAccountSelector;
  private final Instant createdAt;
  private final Instant updatedAt;

  @SuppressWarnings("java:S107") // one parameter per persisted column
  private SocialConnectionSettings(
      final UUID id,
      final UUID organizationId,
      final SocialProvider provider,
      final boolean blockEmailSubaddresses,
      final boolean alwaysShowAccountSelector,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.blockEmailSubaddresses = blockEmailSubaddresses;
    this.alwaysShowAccountSelector = alwaysShowAccountSelector;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }

  /** What an Organization that never saved its connection gets: nothing blocked, no chooser. */
  public static SocialConnectionSettings unset(
      final UUID organizationId, final SocialProvider provider) {
    return define(organizationId, provider, false, false);
  }

  public static SocialConnectionSettings define(
      final UUID organizationId,
      final SocialProvider provider,
      final boolean blockEmailSubaddresses,
      final boolean alwaysShowAccountSelector) {
    final Instant now = Instant.now();
    return new SocialConnectionSettings(
        UUID.randomUUID(),
        organizationId,
        provider,
        blockEmailSubaddresses,
        alwaysShowAccountSelector,
        now,
        now);
  }

  /** Replaces the values in place, keeping the row's own id and creation time. */
  public SocialConnectionSettings with(
      final boolean blockEmailSubaddresses, final boolean alwaysShowAccountSelector) {
    return new SocialConnectionSettings(
        id,
        organizationId,
        provider,
        blockEmailSubaddresses,
        alwaysShowAccountSelector,
        createdAt,
        Instant.now());
  }

  @SuppressWarnings("java:S107")
  public static SocialConnectionSettings reconstitute(
      final UUID id,
      final UUID organizationId,
      final SocialProvider provider,
      final boolean blockEmailSubaddresses,
      final boolean alwaysShowAccountSelector,
      final Instant createdAt,
      final Instant updatedAt) {
    return new SocialConnectionSettings(
        id,
        organizationId,
        provider,
        blockEmailSubaddresses,
        alwaysShowAccountSelector,
        createdAt,
        updatedAt);
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public SocialProvider provider() {
    return provider;
  }

  public boolean blockEmailSubaddresses() {
    return blockEmailSubaddresses;
  }

  public boolean alwaysShowAccountSelector() {
    return alwaysShowAccountSelector;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}

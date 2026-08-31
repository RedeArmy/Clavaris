package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code social_identities} (migration {@code V20260828090000}, ADR-0020,
 * BR-ID-09). {@code provider} is a plain {@code String}, not the domain's {@code SocialProvider}
 * enum — same convention as {@code VerificationTokenEntity.type}: this entity never references a
 * {@code domain.model} type at all, {@code JpaSocialIdentityRepository} owns the conversion.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "social_identities")
public class SocialIdentityEntity {

  @Id private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false, length = 32)
  private String provider;

  @Column(name = "provider_user_id", nullable = false)
  private String providerUserId;

  @Column(name = "linked_at", nullable = false)
  private Instant linkedAt;

  protected SocialIdentityEntity() {}

  public SocialIdentityEntity(
      final UUID id,
      final UUID accountId,
      final UUID organizationId,
      final String provider,
      final String providerUserId,
      final Instant linkedAt) {
    this.id = id;
    this.accountId = accountId;
    this.organizationId = organizationId;
    this.provider = provider;
    this.providerUserId = providerUserId;
    this.linkedAt = linkedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getProvider() {
    return provider;
  }

  public String getProviderUserId() {
    return providerUserId;
  }

  public Instant getLinkedAt() {
    return linkedAt;
  }
}

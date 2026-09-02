package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code webhook_endpoints} (migration {@code V20260902120000}, ADR-0007
 * §1/§2). {@code subscribedEventTypes} is stored as {@code text} (JSON array) — same convention as
 * {@code OAuthClientEntity}'s own three JSON-array columns; serialization happens in {@link
 * JpaWebhookEndpointRepository}, not here.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable", "PMD.LongVariable"})
@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpointEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private String url;

  private String description;

  @Column(name = "subscribed_event_types", nullable = false, columnDefinition = "text")
  private String subscribedEventTypes;

  @Column(name = "current_secret_encrypted", nullable = false, columnDefinition = "text")
  private String currentSecretEncrypted;

  @Column(name = "previous_secret_encrypted", columnDefinition = "text")
  private String previousSecretEncrypted;

  @Column(name = "previous_secret_expires_at")
  private Instant previousSecretExpiresAt;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected WebhookEndpointEntity() {}

  // One parameter per persisted column — same convention as every other *Entity in this codebase
  // (OAuthClientEntity, PasswordCredentialEntity, ...).
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public WebhookEndpointEntity(
      final UUID id,
      final UUID organizationId,
      final String url,
      final String description,
      final String subscribedEventTypes,
      final String currentSecretEncrypted,
      final String previousSecretEncrypted,
      final Instant previousSecretExpiresAt,
      final boolean active,
      final Instant createdAt) {
    this.id = id;
    this.organizationId = organizationId;
    this.url = url;
    this.description = description;
    this.subscribedEventTypes = subscribedEventTypes;
    this.currentSecretEncrypted = currentSecretEncrypted;
    this.previousSecretEncrypted = previousSecretEncrypted;
    this.previousSecretExpiresAt = previousSecretExpiresAt;
    this.active = active;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getUrl() {
    return url;
  }

  public String getDescription() {
    return description;
  }

  public String getSubscribedEventTypes() {
    return subscribedEventTypes;
  }

  public String getCurrentSecretEncrypted() {
    return currentSecretEncrypted;
  }

  public String getPreviousSecretEncrypted() {
    return previousSecretEncrypted;
  }

  public Instant getPreviousSecretExpiresAt() {
    return previousSecretExpiresAt;
  }

  public boolean isActive() {
    return active;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code redirect_policies} — plain data holder by design, same rationale as
 * {@code RateLimitPolicyEntity}. Every URL column is nullable — unconfigured is the normal state.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass", "PMD.LongVariable"})
@Entity
@Table(name = "redirect_policies")
public class RedirectPolicyEntity {

  @Id private UUID id;

  @Column(name = "oauth_client_id", nullable = false)
  private UUID oauthClientId;

  @Column(name = "fallback_sign_in_redirect_url")
  private String fallbackSignInRedirectUrl;

  @Column(name = "fallback_sign_up_redirect_url")
  private String fallbackSignUpRedirectUrl;

  @Column(name = "force_sign_in_redirect_url")
  private String forceSignInRedirectUrl;

  @Column(name = "force_sign_up_redirect_url")
  private String forceSignUpRedirectUrl;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected RedirectPolicyEntity() {}

  // One parameter per persisted column — same convention as every other *Entity in this codebase.
  public RedirectPolicyEntity(
      final UUID id,
      final UUID oauthClientId,
      final String fallbackSignInRedirectUrl,
      final String fallbackSignUpRedirectUrl,
      final String forceSignInRedirectUrl,
      final String forceSignUpRedirectUrl,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = id;
    this.oauthClientId = oauthClientId;
    this.fallbackSignInRedirectUrl = fallbackSignInRedirectUrl;
    this.fallbackSignUpRedirectUrl = fallbackSignUpRedirectUrl;
    this.forceSignInRedirectUrl = forceSignInRedirectUrl;
    this.forceSignUpRedirectUrl = forceSignUpRedirectUrl;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOauthClientId() {
    return oauthClientId;
  }

  public String getFallbackSignInRedirectUrl() {
    return fallbackSignInRedirectUrl;
  }

  public String getFallbackSignUpRedirectUrl() {
    return fallbackSignUpRedirectUrl;
  }

  public String getForceSignInRedirectUrl() {
    return forceSignInRedirectUrl;
  }

  public String getForceSignUpRedirectUrl() {
    return forceSignUpRedirectUrl;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}

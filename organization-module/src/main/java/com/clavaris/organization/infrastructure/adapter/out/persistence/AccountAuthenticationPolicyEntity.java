package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.domain.model.EmailVerificationMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code account_authentication_policies} (ADR-0024) — plain data holder by
 * design, same convention as {@code RateLimitPolicyEntity}.
 */
@SuppressWarnings({
  "PMD.ShortVariable",
  "PMD.DataClass",
  "PMD.LongVariable",
  "PMD.ExcessiveParameterList"
})
@Entity
@Table(name = "account_authentication_policies")
public class AccountAuthenticationPolicyEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "email_verification_required_at_sign_in", nullable = false)
  private boolean emailVerificationRequiredAtSignIn;

  @Enumerated(EnumType.STRING)
  @Column(name = "email_verification_method", nullable = false, length = 10)
  private EmailVerificationMethod emailVerificationMethod;

  @Column(name = "email_code_sign_in_enabled", nullable = false)
  private boolean emailCodeSignInEnabled;

  @Column(name = "email_link_sign_in_enabled", nullable = false)
  private boolean emailLinkSignInEnabled;

  @Column(name = "username_sign_up_enabled", nullable = false)
  private boolean usernameSignUpEnabled;

  @Column(name = "username_required", nullable = false)
  private boolean usernameRequired;

  @Column(name = "username_sign_in_enabled", nullable = false)
  private boolean usernameSignInEnabled;

  @Column(name = "password_at_sign_up_enabled", nullable = false)
  private boolean passwordAtSignUpEnabled;

  @Column(name = "device_trust_enabled", nullable = false)
  private boolean deviceTrustEnabled;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected AccountAuthenticationPolicyEntity() {}

  @SuppressWarnings("java:S107")
  public AccountAuthenticationPolicyEntity(
      final UUID id,
      final UUID organizationId,
      final boolean emailVerificationRequiredAtSignIn,
      final EmailVerificationMethod emailVerificationMethod,
      final boolean emailCodeSignInEnabled,
      final boolean emailLinkSignInEnabled,
      final boolean usernameSignUpEnabled,
      final boolean usernameRequired,
      final boolean usernameSignInEnabled,
      final boolean passwordAtSignUpEnabled,
      final boolean deviceTrustEnabled,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = id;
    this.organizationId = organizationId;
    this.emailVerificationRequiredAtSignIn = emailVerificationRequiredAtSignIn;
    this.emailVerificationMethod = emailVerificationMethod;
    this.emailCodeSignInEnabled = emailCodeSignInEnabled;
    this.emailLinkSignInEnabled = emailLinkSignInEnabled;
    this.usernameSignUpEnabled = usernameSignUpEnabled;
    this.usernameRequired = usernameRequired;
    this.usernameSignInEnabled = usernameSignInEnabled;
    this.passwordAtSignUpEnabled = passwordAtSignUpEnabled;
    this.deviceTrustEnabled = deviceTrustEnabled;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public boolean isEmailVerificationRequiredAtSignIn() {
    return emailVerificationRequiredAtSignIn;
  }

  public EmailVerificationMethod getEmailVerificationMethod() {
    return emailVerificationMethod;
  }

  public boolean isEmailCodeSignInEnabled() {
    return emailCodeSignInEnabled;
  }

  public boolean isEmailLinkSignInEnabled() {
    return emailLinkSignInEnabled;
  }

  public boolean isUsernameSignUpEnabled() {
    return usernameSignUpEnabled;
  }

  public boolean isUsernameRequired() {
    return usernameRequired;
  }

  public boolean isUsernameSignInEnabled() {
    return usernameSignInEnabled;
  }

  public boolean isPasswordAtSignUpEnabled() {
    return passwordAtSignUpEnabled;
  }

  public boolean isDeviceTrustEnabled() {
    return deviceTrustEnabled;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}

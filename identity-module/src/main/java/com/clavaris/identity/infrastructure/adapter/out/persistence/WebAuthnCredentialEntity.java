package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA row mapping for {@code webauthn_credentials} (TD-FUT-034, passkeys parity). */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass"})
@Entity
@Table(name = "webauthn_credentials")
public class WebAuthnCredentialEntity {

  @Id private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "credential_id", nullable = false, unique = true)
  private byte[] credentialId;

  @Column(name = "public_key_cose", nullable = false)
  private byte[] publicKeyCose;

  @Column(name = "signature_count", nullable = false)
  private long signatureCount;

  @Column(name = "transports")
  private String transports;

  @Column(name = "nickname")
  private String nickname;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  protected WebAuthnCredentialEntity() {
    // JPA only.
  }

  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public WebAuthnCredentialEntity(
      final UUID id,
      final UUID accountId,
      final UUID organizationId,
      final byte[] credentialId,
      final byte[] publicKeyCose,
      final long signatureCount,
      final String transports,
      final String nickname,
      final Instant createdAt,
      final Instant lastUsedAt) {
    this.id = id;
    this.accountId = accountId;
    this.organizationId = organizationId;
    this.credentialId = credentialId.clone();
    this.publicKeyCose = publicKeyCose.clone();
    this.signatureCount = signatureCount;
    this.transports = transports;
    this.nickname = nickname;
    this.createdAt = createdAt;
    this.lastUsedAt = lastUsedAt;
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

  public byte[] getCredentialId() {
    return credentialId.clone();
  }

  public byte[] getPublicKeyCose() {
    return publicKeyCose.clone();
  }

  public long getSignatureCount() {
    return signatureCount;
  }

  public void setSignatureCount(final long signatureCount) {
    this.signatureCount = signatureCount;
  }

  public String getTransports() {
    return transports;
  }

  public String getNickname() {
    return nickname;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public void setLastUsedAt(final Instant lastUsedAt) {
    this.lastUsedAt = lastUsedAt;
  }
}

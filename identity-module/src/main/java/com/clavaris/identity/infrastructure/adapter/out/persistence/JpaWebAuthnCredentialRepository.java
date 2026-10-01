package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.WebAuthnCredential;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.WebAuthnCredential} and {@link
 * WebAuthnCredentialEntity}.
 */
@Repository
class JpaWebAuthnCredentialRepository implements WebAuthnCredentialRepository {

  private final SpringDataWebAuthnCredentialJpaRepository credentials;
  private final EntityManager entityManager;

  /* package */ JpaWebAuthnCredentialRepository(
      final SpringDataWebAuthnCredentialJpaRepository credentials,
      final EntityManager entityManager) {
    this.credentials = credentials;
    this.entityManager = entityManager;
  }

  @Override
  public void insert(final WebAuthnCredential credential) {
    entityManager.persist(toEntity(credential));
  }

  @Override
  public Optional<WebAuthnCredential> findByCredentialId(final byte[] credentialId) {
    return credentials.findByCredentialId(credentialId).map(this::toDomain);
  }

  @Override
  public List<WebAuthnCredential> findAllByAccountId(final AccountId accountId) {
    return credentials.findAllByAccountIdOrderByCreatedAtDesc(accountId.value()).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public void updateSignatureCount(
      final UUID credentialRowId, final long newSignatureCount, final Instant lastUsedAt) {
    final WebAuthnCredentialEntity entity =
        entityManager.find(WebAuthnCredentialEntity.class, credentialRowId);
    if (entity != null) {
      entity.setSignatureCount(newSignatureCount);
      entity.setLastUsedAt(lastUsedAt);
    }
  }

  @Override
  public boolean deleteByIdAndAccountId(final UUID credentialRowId, final AccountId accountId) {
    return credentials.deleteByIdAndAccountId(credentialRowId, accountId.value()) > 0;
  }

  private WebAuthnCredentialEntity toEntity(final WebAuthnCredential credential) {
    return new WebAuthnCredentialEntity(
        credential.id(),
        credential.accountId().value(),
        credential.organizationId().value(),
        credential.credentialId(),
        credential.publicKeyCose(),
        credential.signatureCount(),
        credential.transports(),
        credential.nickname(),
        credential.createdAt(),
        credential.lastUsedAt());
  }

  private WebAuthnCredential toDomain(final WebAuthnCredentialEntity entity) {
    return WebAuthnCredential.reconstitute(
        entity.getId(),
        new AccountId(entity.getAccountId()),
        new OrganizationId(entity.getOrganizationId()),
        entity.getCredentialId(),
        entity.getPublicKeyCose(),
        entity.getSignatureCount(),
        entity.getTransports(),
        entity.getNickname(),
        entity.getCreatedAt(),
        entity.getLastUsedAt());
  }
}

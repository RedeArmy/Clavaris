package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

interface SpringDataWebAuthnCredentialJpaRepository
    extends JpaRepository<WebAuthnCredentialEntity, UUID> {

  Optional<WebAuthnCredentialEntity> findByCredentialId(byte[] credentialId);

  List<WebAuthnCredentialEntity> findAllByAccountIdOrderByCreatedAtDesc(UUID accountId);

  // Ownership-scoped: both the self-service and admin delete paths pass the resolved target
  // Account's own id, never trusting a credentialId alone — a mismatched accountId simply deletes
  // nothing (returns 0), the same "not found or not yours" outcome either caller treats
  // identically. PMD.ShortVariable: id names exactly what it is — same KnownDevice-style precedent.
  @SuppressWarnings("PMD.ShortVariable")
  @Modifying
  int deleteByIdAndAccountId(UUID id, UUID accountId);
}

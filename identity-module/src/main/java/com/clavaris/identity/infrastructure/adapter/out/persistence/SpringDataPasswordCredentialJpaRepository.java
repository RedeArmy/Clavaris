package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPasswordCredentialJpaRepository
    extends JpaRepository<PasswordCredentialEntity, UUID> {

  Optional<PasswordCredentialEntity> findByAccountId(UUID accountId);

  /**
   * TD-PERF-029: batch-by-accountId for a caller converting a whole page of {@code AccountEntity}
   * rows at once ({@code JpaAccountRepository#findKeysetPageByOrganizationId}) — one query for
   * every distinct account in the page, instead of {@link #findByAccountId} once per row.
   */
  List<PasswordCredentialEntity> findByAccountIdIn(Collection<UUID> accountIds);
}

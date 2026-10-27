package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.requestemailchange.EmailChangeRequestRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.EmailChangeRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaEmailChangeRequestRepository implements EmailChangeRequestRepository {

  private final SpringDataEmailChangeRequestJpaRepository requests;

  /* package */ JpaEmailChangeRequestRepository(
      final SpringDataEmailChangeRequestJpaRepository requests) {
    this.requests = requests;
  }

  @Override
  @Transactional
  public void replaceFor(final EmailChangeRequest request) {
    // Delete flushes first (flushAutomatically), so the UNIQUE(account_id) never sees both rows.
    requests.deleteAllByAccountId(request.accountId().value());
    requests.saveAndFlush(
        new EmailChangeRequestEntity(
            request.id(),
            request.accountId().value(),
            request.newEmail().value(),
            request.tokenHash(),
            request.expiresAt()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<EmailChangeRequest> findByTokenHash(final String tokenHash) {
    return requests
        .findByTokenHash(tokenHash)
        .map(
            entity ->
                new EmailChangeRequest(
                    entity.getId(),
                    new AccountId(entity.getAccountId()),
                    new Email(entity.getNewEmail()),
                    entity.getTokenHash(),
                    entity.getExpiresAt()));
  }

  @Override
  @Transactional
  public boolean consumeIfActive(final UUID requestId, final Instant consumedAt) {
    return requests.consumeIfActive(requestId, consumedAt) == 1;
  }

  @Override
  @Transactional
  public void deleteAllForAccount(final AccountId accountId) {
    requests.deleteAllByAccountId(accountId.value());
  }
}

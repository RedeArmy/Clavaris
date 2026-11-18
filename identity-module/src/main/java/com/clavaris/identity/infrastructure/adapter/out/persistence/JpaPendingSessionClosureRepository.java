package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.deferaccountsessionclosure.PendingSessionClosureRepository;
import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaPendingSessionClosureRepository implements PendingSessionClosureRepository {

  private final SpringDataPendingSessionClosureJpaRepository pending;

  /* package */ JpaPendingSessionClosureRepository(
      final SpringDataPendingSessionClosureJpaRepository pending) {
    this.pending = pending;
  }

  // Joins the caller's transaction when there is one (the suspension's), so the row exists exactly
  // when the state change does.
  @Override
  @Transactional
  public void add(final AccountId accountId, final Instant recordedAt) {
    pending.insertIfAbsent(accountId.value(), recordedAt);
  }

  @Override
  @Transactional(readOnly = true)
  public List<AccountId> oldest(final int limit) {
    return pending.oldest(PageRequest.of(0, limit)).stream().map(AccountId::new).toList();
  }

  @Override
  @Transactional
  public void remove(final AccountId accountId) {
    pending.deleteByAccount(accountId.value());
  }
}

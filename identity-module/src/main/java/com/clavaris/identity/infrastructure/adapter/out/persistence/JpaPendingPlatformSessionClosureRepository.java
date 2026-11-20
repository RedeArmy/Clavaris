package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.deferplatformsessionclosure.PendingPlatformSessionClosureRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaPendingPlatformSessionClosureRepository
    implements PendingPlatformSessionClosureRepository {

  private final SpringDataPendingPlatformSessionClosureJpaRepository pending;

  /* package */ JpaPendingPlatformSessionClosureRepository(
      final SpringDataPendingPlatformSessionClosureJpaRepository pending) {
    this.pending = pending;
  }

  // Joins the caller's transaction when there is one (the suspension's), so the row exists exactly
  // when the state change does.
  @Override
  @Transactional
  public void add(final PlatformAccountId platformAccountId, final Instant recordedAt) {
    pending.insertIfAbsent(platformAccountId.value(), recordedAt);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PlatformAccountId> oldest(final int limit) {
    return pending.oldest(PageRequest.of(0, limit)).stream().map(PlatformAccountId::new).toList();
  }

  @Override
  @Transactional
  public void remove(final PlatformAccountId platformAccountId) {
    pending.deleteByAccount(platformAccountId.value());
  }

  @Override
  @Transactional(readOnly = true)
  public long count() {
    return pending.count();
  }
}

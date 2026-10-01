package com.clavaris.identity.application.usecase.recordloginevent;

import com.clavaris.identity.application.usecase.getloginactivityforaccount.LoginActivityDay;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.LoginEvent;
import java.time.Instant;
import java.util.List;

/**
 * Outbound port — implemented in {@code
 * infrastructure/adapter/out/persistence/JpaLoginEventRepository}. Both {@link
 * RecordLoginEventService} (write) and {@code GetLoginActivityForAccountService} (read) depend on
 * this one interface, same "one port per table, used by every use case that touches it" convention
 * {@code KnownDeviceRepository} already establishes.
 */
public interface LoginEventRepository {

  void insert(LoginEvent event);

  /**
   * TD-FUT-034: one row per calendar day (in the database's own session time zone — UTC, same as
   * every other {@code timestamptz} column in this schema) within {@code [since, now]} that had at
   * least one login, with that day's own login count — the shape the "View Profile" activity
   * heatmap renders directly, a day with no login simply absent rather than a zero-count row, same
   * "don't materialize what the caller doesn't need" posture {@code
   * ListActiveSessionsForAccountUseCase}'s own bounded, ungenerated-empty-days result already
   * follows.
   */
  List<LoginActivityDay> countsByDaySince(AccountId accountId, Instant since);
}

package com.clavaris.identity.application.usecase.getloginactivityforaccount;

import com.clavaris.identity.application.usecase.recordloginevent.LoginEventRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Orchestration for {@link GetLoginActivityForAccountUseCase}. 365 trailing days, same window
 * Clerk's/GitHub's own contribution-style heatmap uses — a fixed constant, not configurable, since
 * nothing in this feature's own scope needs a different window.
 */
public class GetLoginActivityForAccountService implements GetLoginActivityForAccountUseCase {

  // Public so the "View Profile" heatmap's own grid-building code (PlatformAccountDetailController)
  // can size its calendar to the exact same window this query actually covers, rather than
  // duplicating the literal 365 and risking the two silently drifting apart.
  public static final int WINDOW_DAYS = 365;

  private final LoginEventRepository loginEvents;

  public GetLoginActivityForAccountService(final LoginEventRepository loginEvents) {
    this.loginEvents = loginEvents;
  }

  @Override
  public List<LoginActivityDay> handle(final GetLoginActivityForAccountQuery query) {
    final Instant since = Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
    return loginEvents.countsByDaySince(query.accountId(), since);
  }
}

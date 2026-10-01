package com.clavaris.identity.application.usecase.getloginactivityforaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.recordloginevent.LoginEventRepository;
import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GetLoginActivityForAccountServiceTest {

  private LoginEventRepository loginEvents;
  private GetLoginActivityForAccountService service;
  private AccountId accountId;

  @BeforeEach
  void setUp() {
    loginEvents = mock(LoginEventRepository.class);
    service = new GetLoginActivityForAccountService(loginEvents);
    accountId = AccountId.newId();
  }

  @Test
  void returnsExactlyWhatTheRepositoryReturns() {
    List<LoginActivityDay> expected = List.of(new LoginActivityDay(LocalDate.now(), 3));
    when(loginEvents.countsByDaySince(eq(accountId), any())).thenReturn(expected);

    List<LoginActivityDay> result = service.handle(new GetLoginActivityForAccountQuery(accountId));

    assertThat(result).isEqualTo(expected);
  }

  // GetLoginActivityForAccountService.WINDOW_DAYS (365) is the one thing this class actually
  // owns — same GitHub/Clerk-style trailing-window convention this feature's own Javadoc cites.
  @Test
  void queriesExactlyA365DayTrailingWindow() {
    when(loginEvents.countsByDaySince(eq(accountId), any())).thenReturn(List.of());

    service.handle(new GetLoginActivityForAccountQuery(accountId));

    ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
    verify(loginEvents).countsByDaySince(eq(accountId), since.capture());
    Instant expectedSince =
        Instant.now().minus(GetLoginActivityForAccountService.WINDOW_DAYS, ChronoUnit.DAYS);
    assertThat(since.getValue()).isCloseTo(expectedSince, within(5000, ChronoUnit.MILLIS));
  }
}

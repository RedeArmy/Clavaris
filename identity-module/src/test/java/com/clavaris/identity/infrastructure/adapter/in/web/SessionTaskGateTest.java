package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.identity.domain.model.Username;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** ADR-0031: which task, if any, a just-authenticated login is paused for. */
class SessionTaskGateTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private MockHttpServletRequest request;
  private Account account;

  @BeforeEach
  void setUp() {
    request = new MockHttpServletRequest();
    account = Account.register(new OrganizationId(ORGANIZATION_ID), new Email("ada@example.com"));
  }

  private java.util.Optional<String> intercept(final boolean chooseUsername) {
    return SessionTaskGate.intercept(
        request,
        ORGANIZATION_ID,
        account,
        PendingAuthenticationFactor.SOCIAL,
        null,
        null,
        SocialProvider.GOOGLE,
        chooseUsername);
  }

  @Test
  void pausesForAUsernameWhenAskedAndTheAccountHasNone() {
    assertThat(intercept(true)).contains("/o/" + ORGANIZATION_ID + "/login/session-task/username");
    assertThat(request.getSession(false).getAttribute(SessionTaskPendingState.TASK_ATTRIBUTE))
        .isEqualTo(SessionTaskPendingState.TASK_USERNAME);
    assertThat(request.getSession(false).getAttribute(SessionTaskPendingState.PROVIDER_ATTRIBUTE))
        .isEqualTo("GOOGLE");
  }

  @Test
  void doesNotPauseWhenNotAskedEvenForAnAccountWithoutAUsername() {
    assertThat(intercept(false)).isEmpty();
    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void doesNotPauseWhenTheAccountAlreadyHasAUsername() {
    account.assignUsername(new Username("ada_l"));

    assertThat(intercept(true)).isEmpty();
  }

  @Test
  void aForcedPasswordResetTakesPrecedenceOverTheUsername() {
    account.requirePasswordReset();

    assertThat(intercept(true))
        .contains("/o/" + ORGANIZATION_ID + "/login/session-task/password-reset");
    assertThat(request.getSession(false).getAttribute(SessionTaskPendingState.TASK_ATTRIBUTE))
        .isEqualTo(SessionTaskPendingState.TASK_PASSWORD_RESET);
  }

  @Test
  void theOriginalSevenArgumentFormStillOnlyKnowsTheForcedPasswordReset() {
    assertThat(
            SessionTaskGate.intercept(
                request,
                ORGANIZATION_ID,
                account,
                PendingAuthenticationFactor.PASSWORD,
                null,
                null,
                null))
        .isEmpty();
  }
}

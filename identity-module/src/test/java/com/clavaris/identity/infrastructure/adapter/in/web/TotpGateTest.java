package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.requiresecondfactor.RequiresSecondFactorUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TotpGateTest {

  private final UUID organizationId = UUID.randomUUID();
  private final AccountId accountId = AccountId.newId();
  private RequiresSecondFactorUseCase requires;
  private MockHttpServletRequest request;

  @BeforeEach
  void setUp() {
    requires = mock(RequiresSecondFactorUseCase.class);
    request = new MockHttpServletRequest();
  }

  @Test
  void anAccountThatNeedsNoSecondFactorIsNotPausedAndNothingIsStored() {
    when(requires.handle(accountId)).thenReturn(false);

    assertThat(
            TotpGate.intercept(
                requires,
                request,
                organizationId,
                accountId,
                PendingAuthenticationFactor.PASSWORD,
                null,
                null,
                null,
                false))
        .isEmpty();
    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void aPasskeyIsNeverPausedEvenForAnAccountThatNeedsOne() {
    when(requires.handle(accountId)).thenReturn(true);

    assertThat(
            TotpGate.intercept(
                requires,
                request,
                organizationId,
                accountId,
                PendingAuthenticationFactor.PASSKEY,
                null,
                null,
                null,
                false))
        .isEmpty();
  }

  @Test
  void aPasswordSignInOfAnAccountThatNeedsOneIsPausedAndTheSignInIsParked() {
    when(requires.handle(accountId)).thenReturn(true);

    assertThat(
            TotpGate.intercept(
                requires,
                request,
                organizationId,
                accountId,
                PendingAuthenticationFactor.PASSWORD,
                "web",
                "https://app.example/cb",
                null,
                true))
        .contains("/o/" + organizationId + "/login/totp");

    final var session = request.getSession(false);
    assertThat(session.getAttribute(TotpPendingState.ACCOUNT_ID_ATTRIBUTE))
        .isEqualTo(accountId.value().toString());
    assertThat(session.getAttribute(TotpPendingState.FACTOR_ATTRIBUTE)).isEqualTo("PASSWORD");
    assertThat(session.getAttribute(TotpPendingState.ORGANIZATION_ID_ATTRIBUTE))
        .isEqualTo(organizationId.toString());
    assertThat(session.getAttribute(TotpPendingState.CLIENT_ID_ATTRIBUTE)).isEqualTo("web");
    assertThat(session.getAttribute(TotpPendingState.DEVICE_TRUST_VERIFIED_ATTRIBUTE))
        .isEqualTo("true");
    // TD-SEC-093: each pause is numbered, so its entries are counted for it alone.
    assertThat(session.getAttribute(TotpPendingState.CHALLENGE_ID_ATTRIBUTE))
        .isInstanceOf(String.class)
        .asString()
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  @Test
  void aSocialSignInKeepsItsProviderAcrossThePause() {
    when(requires.handle(accountId)).thenReturn(true);

    TotpGate.intercept(
        requires,
        request,
        organizationId,
        accountId,
        PendingAuthenticationFactor.SOCIAL,
        null,
        null,
        SocialProvider.GITHUB,
        false);

    assertThat(request.getSession(false).getAttribute(TotpPendingState.PROVIDER_ATTRIBUTE))
        .isEqualTo("GITHUB");
  }

  @Test
  void aValueLeftByAnEarlierAbandonedSignInDoesNotRideIntoThisOne() {
    when(requires.handle(accountId)).thenReturn(true);
    request.getSession().setAttribute(TotpPendingState.CLIENT_ID_ATTRIBUTE, "stale");

    TotpGate.intercept(
        requires,
        request,
        organizationId,
        accountId,
        PendingAuthenticationFactor.PASSWORD,
        null,
        null,
        null,
        false);

    assertThat(request.getSession(false).getAttribute(TotpPendingState.CLIENT_ID_ATTRIBUTE))
        .isNull();
  }

  @Test
  void aResumedPauseHandsItsValuesOverAndLeavesNothingOfTheEarlierOneToReplay() {
    when(requires.handle(accountId)).thenReturn(true);
    final var session = request.getSession();
    session.setAttribute("f", "SOCIAL");
    session.setAttribute("c", "web");
    session.setAttribute("r", "https://app.example/cb");
    session.setAttribute("p", "GOOGLE");

    assertThat(
            TotpGate.interceptResumed(
                requires, request, session, organizationId, accountId, "f", "c", "r", "p", true))
        .contains("/o/" + organizationId + "/login/totp");

    assertThat(session.getAttribute("f")).isNull();
    assertThat(session.getAttribute("c")).isNull();
    assertThat(session.getAttribute(TotpPendingState.FACTOR_ATTRIBUTE)).isEqualTo("SOCIAL");
    assertThat(session.getAttribute(TotpPendingState.PROVIDER_ATTRIBUTE)).isEqualTo("GOOGLE");
    assertThat(session.getAttribute(TotpPendingState.DEVICE_TRUST_VERIFIED_ATTRIBUTE))
        .isEqualTo("true");
  }

  @Test
  void aResumedPauseOfAnAccountThatNeedsNothingLeavesTheEarlierOneUntouched() {
    when(requires.handle(accountId)).thenReturn(false);
    final var session = request.getSession();
    session.setAttribute("f", "PASSWORD");

    assertThat(
            TotpGate.interceptResumed(
                requires, request, session, organizationId, accountId, "f", "c", "r", "p", false))
        .isEmpty();
    assertThat(session.getAttribute("f")).isEqualTo("PASSWORD");
  }

  // TD-ARCH-045: without the earlier pause's factor there is nothing to carry over, and the gate
  // does not guess one (a passkey must never be paused, so a wrong guess either way is a hole).
  @Test
  void aResumedPauseWithNoFactorIsLeftToTheCallerAndAsksNothing() {
    final org.springframework.mock.web.MockHttpSession session =
        new org.springframework.mock.web.MockHttpSession();

    assertThat(
            TotpGate.interceptResumed(
                requires,
                request,
                session,
                organizationId,
                accountId,
                "factor",
                "client",
                "redirect",
                "provider",
                false))
        .isEmpty();
    org.mockito.Mockito.verifyNoInteractions(requires);
  }
}

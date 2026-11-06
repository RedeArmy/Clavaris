package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventUseCase;
import com.clavaris.identity.application.usecase.requiresecondfactor.RequiresSecondFactorUseCase;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.AccountId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

/**
 * TD-ARCH-045: resuming a pending sign-in whose factor has gone missing from the session. The
 * attributes are set and cleared together, so this is a corrupted or truncated session; it used to
 * end in a server error, and must end at the sign-in page without completing anything.
 */
class AuthenticatedSessionCompletionTest {

  private static final String ACCOUNT = "pending.account";
  private static final String FACTOR = "pending.factor";
  private static final String ORGANIZATION = "pending.organization";
  private static final String CLIENT = "pending.client";
  private static final String REDIRECT = "pending.redirect";
  private static final String PROVIDER = "pending.provider";

  private final UUID organizationId = UUID.randomUUID();
  private final AccountId accountId = AccountId.newId();
  private AuthenticatedSessionEstablisher sessions;
  private RequiresSecondFactorUseCase requiresSecondFactor;
  private MockHttpSession session;
  private MockHttpServletRequest request;

  @BeforeEach
  void setUp() {
    sessions = mock(AuthenticatedSessionEstablisher.class);
    requiresSecondFactor = mock(RequiresSecondFactorUseCase.class);
    session = new MockHttpSession();
    request = new MockHttpServletRequest();
    request.setSession(session);
    session.setAttribute(ACCOUNT, accountId.value().toString());
    session.setAttribute(ORGANIZATION, organizationId.toString());
  }

  private String resume() {
    return AuthenticatedSessionCompletion.completeFromPendingChallenge(
        sessions,
        mock(RecordAccountLoginDeviceUseCase.class),
        mock(RedirectUrlResolver.class),
        mock(RecordLoginEventUseCase.class),
        request,
        new MockHttpServletResponse(),
        session,
        organizationId,
        accountId,
        ACCOUNT,
        FACTOR,
        ORGANIZATION,
        CLIENT,
        REDIRECT,
        PROVIDER,
        requiresSecondFactor,
        false);
  }

  private void assertNothingPendingRemains() {
    assertThat(List.of(ACCOUNT, FACTOR, ORGANIZATION, CLIENT, REDIRECT, PROVIDER))
        .allSatisfy(name -> assertThat(session.getAttribute(name)).as(name).isNull());
  }

  @Test
  void aMissingFactorSendsThePersonBackToSignInAndCompletesNothing() {
    assertThat(resume()).isEqualTo("/o/" + organizationId + "/login");

    verifyNoInteractions(sessions);
    assertNothingPendingRemains();
  }

  @Test
  void aFactorThatIsNotAKnownOneIsTreatedTheSameWay() {
    session.setAttribute(FACTOR, "NOT_A_FACTOR");

    assertThat(resume()).isEqualTo("/o/" + organizationId + "/login");

    verifyNoInteractions(sessions);
    assertNothingPendingRemains();
  }

  // The dangerous reading of "nothing to resume" is "no second factor needed": that would complete
  // the sign-in without the authenticator code. The gate must not even be asked.
  @Test
  void aMissingFactorNeverSkipsTheSecondFactorByCompleting() {
    resume();

    verifyNoInteractions(requiresSecondFactor, sessions);
  }
}

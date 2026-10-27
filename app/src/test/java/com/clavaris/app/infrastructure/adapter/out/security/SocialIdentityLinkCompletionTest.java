package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.clavaris.identity.application.usecase.connectsocialidentity.ConnectSocialIdentityCommand;
import com.clavaris.identity.application.usecase.connectsocialidentity.ConnectSocialIdentityUseCase;
import com.clavaris.identity.application.usecase.connectsocialidentity.SocialIdentityAlreadyLinkedException;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.identity.infrastructure.adapter.in.web.AuthenticatedSessionEstablisher;
import com.clavaris.identity.infrastructure.adapter.in.web.SocialLoginRedirectController;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class SocialIdentityLinkCompletionTest {

  private final UUID organizationId = UUID.randomUUID();
  private final UUID accountId = UUID.randomUUID();

  private ConnectSocialIdentityUseCase connect;
  private AuthenticatedSessionEstablisher sessions;
  private SocialIdentityLinkCompletion completion;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private MockHttpSession session;

  @BeforeEach
  void setUp() {
    connect = mock(ConnectSocialIdentityUseCase.class);
    sessions = mock(AuthenticatedSessionEstablisher.class);
    completion = new SocialIdentityLinkCompletion(connect, sessions);
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
    session = new MockHttpSession();
    request.setSession(session);
  }

  private void plantIntent(final Instant at) {
    session.setAttribute(
        SocialLoginRedirectController.LINK_ACCOUNT_ID_SESSION_ATTRIBUTE, accountId.toString());
    session.setAttribute(SocialLoginRedirectController.LINK_REQUESTED_AT_SESSION_ATTRIBUTE, at);
  }

  private boolean run() throws Exception {
    return completion.completeIfRequested(
        request, response, session, organizationId.toString(), SocialProvider.GITHUB, "gh-1");
  }

  @Test
  void withoutAnIntentItIsAPlainSignIn() throws Exception {
    assertThat(run()).isFalse();

    verify(connect, never()).handle(any());
  }

  @Test
  void linksToTheStoredAccountRestoresItsSessionAndGoesBackToTheConnectionsPage() throws Exception {
    plantIntent(Instant.now());

    assertThat(run()).isTrue();

    verify(connect)
        .handle(
            new ConnectSocialIdentityCommand(
                new AccountId(accountId), SocialProvider.GITHUB, "gh-1"));
    verify(sessions)
        .establishViaSocialLogin(
            any(),
            any(),
            org.mockito.ArgumentMatchers.eq(accountId),
            any(),
            org.mockito.ArgumentMatchers.eq(false),
            any());
    assertThat(response.getRedirectedUrl())
        .isEqualTo("/o/" + organizationId + "/account/connections?connected");
  }

  @Test
  void theIntentIsSingleUse() throws Exception {
    plantIntent(Instant.now());
    run();

    assertThat(run()).isFalse();
  }

  @Test
  void anExpiredIntentIsIgnoredSoItCanNeverHijackALaterSignIn() throws Exception {
    plantIntent(Instant.now().minusSeconds(3600));

    assertThat(run()).isFalse();
    verify(connect, never()).handle(any());
  }

  @Test
  void aRefusedLinkStillRestoresTheSessionAndReportsIt() throws Exception {
    plantIntent(Instant.now());
    doThrow(new SocialIdentityAlreadyLinkedException()).when(connect).handle(any());

    assertThat(run()).isTrue();

    assertThat(response.getRedirectedUrl()).endsWith("/account/connections?alreadyLinked");
    verify(sessions)
        .establishViaSocialLogin(
            any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
  }
}

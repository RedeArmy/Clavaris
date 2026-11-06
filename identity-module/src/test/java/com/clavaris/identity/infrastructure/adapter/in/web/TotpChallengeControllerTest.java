package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventUseCase;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.application.usecase.verifytotpchallenge.InvalidTotpCodeException;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpMethod;
import com.clavaris.identity.application.usecase.verifytotpchallenge.VerifyTotpChallengeUseCase;
import com.clavaris.identity.domain.model.AccountId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

class TotpChallengeControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final String URL = "/o/{organizationId}/login/totp";

  private final AccountId accountId = AccountId.newId();
  private VerifyTotpChallengeUseCase verify;
  private AuthenticatedSessionEstablisher sessions;
  private RecordAccountLoginDeviceUseCase recordLoginDevice;
  private RecordLoginEventUseCase recordLoginEvent;
  private CodeLockout codeLockout;
  private MockMvc mockMvc;
  private MockHttpSession session;

  @BeforeEach
  void setUp() {
    verify = mock(VerifyTotpChallengeUseCase.class);
    sessions = mock(AuthenticatedSessionEstablisher.class);
    recordLoginDevice = mock(RecordAccountLoginDeviceUseCase.class);
    recordLoginEvent = mock(RecordLoginEventUseCase.class);
    codeLockout = mock(CodeLockout.class);
    final RedirectUrlResolver redirectUrlResolver = mock(RedirectUrlResolver.class);
    when(redirectUrlResolver.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());
    when(recordLoginDevice.handle(any())).thenReturn(Optional.empty());
    when(sessions.establish(any(), any(), any(), anyBoolean(), any())).thenReturn("/o/x/home");

    session = new MockHttpSession();
    session.setAttribute(TotpPendingState.ACCOUNT_ID_ATTRIBUTE, accountId.value().toString());
    session.setAttribute(TotpPendingState.ORGANIZATION_ID_ATTRIBUTE, ORGANIZATION_ID.toString());
    session.setAttribute(TotpPendingState.FACTOR_ATTRIBUTE, "PASSWORD");
    session.setAttribute(TotpPendingState.DEVICE_TRUST_VERIFIED_ATTRIBUTE, "false");

    final GenericApplicationContext applicationContext = new GenericApplicationContext();
    applicationContext.refresh();
    final SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
    templateResolver.setApplicationContext(applicationContext);
    templateResolver.setPrefix("classpath:/templates/");
    templateResolver.setSuffix(".html");
    final SpringTemplateEngine templateEngine = new SpringTemplateEngine();
    templateEngine.setTemplateResolver(templateResolver);
    final ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
    viewResolver.setTemplateEngine(templateEngine);

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new TotpChallengeController(
                    verify,
                    sessions,
                    recordLoginDevice,
                    redirectUrlResolver,
                    recordLoginEvent,
                    codeLockout))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void theFormIsShownForAPausedSignIn() throws Exception {
    mockMvc
        .perform(get(URL, ORGANIZATION_ID).session(session))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-totp"));
  }

  @Test
  void withoutAPausedSignInThePersonIsSentBackToTheLogin() throws Exception {
    mockMvc
        .perform(get(URL, ORGANIZATION_ID))
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login"));
  }

  @Test
  void aPausedSignInOfAnotherOrganizationIsNotUsable() throws Exception {
    mockMvc
        .perform(get(URL, UUID.randomUUID()).session(session))
        .andExpect(status().is3xxRedirection());
  }

  @Test
  void theFormSaysSoWhenTheMethodIsPaused() throws Exception {
    when(codeLockout.isLocked(CodeLockout.totpCodeKey(accountId))).thenReturn(true);

    mockMvc
        .perform(get(URL, ORGANIZATION_ID).session(session))
        .andExpect(model().attribute("codeLocked", true))
        .andExpect(model().attribute("recoveryCodeAvailable", true));
  }

  // TD-SEC-092: the recovery codes are paused apart; the hint to use one is only true while open.
  @Test
  void theFormDoesNotOfferRecoveryCodesWhenTheyArePausedToo() throws Exception {
    when(codeLockout.isLocked(CodeLockout.totpCodeKey(accountId))).thenReturn(true);
    when(codeLockout.isLocked(CodeLockout.totpRecoveryKey(accountId))).thenReturn(true);

    mockMvc
        .perform(get(URL, ORGANIZATION_ID).session(session))
        .andExpect(model().attribute("codeLocked", true))
        .andExpect(model().attributeDoesNotExist("recoveryCodeAvailable"));
  }

  @Test
  void aRightCodeMarksTheSessionAndCompletesTheSignIn() throws Exception {
    when(verify.handle(any())).thenReturn(TotpMethod.AUTHENTICATOR);

    mockMvc
        .perform(post(URL, ORGANIZATION_ID).session(session).param("code", "123456"))
        .andExpect(redirectedUrl("/o/x/home"));

    verify(sessions).establish(any(), any(), eq(accountId.value()), eq(false), any());
    assertThat(session.getAttribute(TotpPendingState.ACCOUNT_ID_ATTRIBUTE)).isNull();
  }

  @Test
  void theMarkIsSetBeforeTheSessionIsBuiltSoTheEstablisherCanSeeIt() throws Exception {
    when(verify.handle(any())).thenReturn(TotpMethod.AUTHENTICATOR);
    when(sessions.establish(any(), any(), any(), anyBoolean(), any()))
        .thenAnswer(
            invocation -> {
              final jakarta.servlet.http.HttpServletRequest request = invocation.getArgument(0);
              assertThat(SecondFactorSessionMarker.consume(request, accountId.value())).isTrue();
              return "/o/x/home";
            });

    mockMvc
        .perform(post(URL, ORGANIZATION_ID).session(session).param("code", "123456"))
        .andExpect(redirectedUrl("/o/x/home"));
  }

  @Test
  void aDeviceTrustChallengePassedEarlierIsStillClaimed() throws Exception {
    session.setAttribute(TotpPendingState.DEVICE_TRUST_VERIFIED_ATTRIBUTE, "true");
    when(verify.handle(any())).thenReturn(TotpMethod.RECOVERY_CODE);

    mockMvc
        .perform(post(URL, ORGANIZATION_ID).session(session).param("code", "ABCD-EFGH-IJKL-MNOP"))
        .andExpect(status().is3xxRedirection());

    verify(sessions).establish(any(), any(), eq(accountId.value()), eq(true), any());
  }

  @Test
  void aWrongCodeShowsTheFormAgainAndBuildsNoSession() throws Exception {
    when(verify.handle(any())).thenThrow(new InvalidTotpCodeException());

    mockMvc
        .perform(post(URL, ORGANIZATION_ID).session(session).param("code", "000000"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("codeError", true));

    verify(sessions, never()).establish(any(), any(), any(), anyBoolean(), any());
  }

  @Test
  void aPausedMethodShowsThePauseAndBuildsNoSession() throws Exception {
    when(verify.handle(any())).thenThrow(new CodeTemporarilyLockedException());

    mockMvc
        .perform(post(URL, ORGANIZATION_ID).session(session).param("code", "123456"))
        .andExpect(model().attribute("codeLocked", true));

    verify(sessions, never()).establish(any(), any(), any(), anyBoolean(), any());
  }

  @Test
  void aBlankCodeIsRejectedBeforeAnythingIsChecked() throws Exception {
    mockMvc
        .perform(post(URL, ORGANIZATION_ID).session(session).param("code", ""))
        .andExpect(status().isOk());

    verify(verify, never()).handle(any());
  }

  @Test
  void withoutAPausedSignInAPostBuildsNothing() throws Exception {
    mockMvc
        .perform(post(URL, ORGANIZATION_ID).param("code", "123456"))
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login"));

    verify(verify, never()).handle(any());
  }
}

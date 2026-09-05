package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.completeforcedpasswordreset.CompleteForcedPasswordResetUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
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

/** Same standalone MockMvc + real Thymeleaf setup as {@link LoginControllerTest}. */
class SessionTaskChallengeControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private CompleteForcedPasswordResetUseCase completeUseCase;
  private AuthenticatedSessionEstablisher sessions;
  private RecordAccountLoginDeviceUseCase recordLoginDevice;
  private RedirectUrlResolver redirectUrlResolver;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    completeUseCase = mock(CompleteForcedPasswordResetUseCase.class);
    sessions = mock(AuthenticatedSessionEstablisher.class);
    recordLoginDevice = mock(RecordAccountLoginDeviceUseCase.class);
    redirectUrlResolver = mock(RedirectUrlResolver.class);
    when(recordLoginDevice.handle(any())).thenReturn(Optional.empty());
    when(redirectUrlResolver.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());

    GenericApplicationContext applicationContext = new GenericApplicationContext();
    applicationContext.refresh();

    SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
    templateResolver.setApplicationContext(applicationContext);
    templateResolver.setPrefix("classpath:/templates/");
    templateResolver.setSuffix(".html");

    SpringTemplateEngine templateEngine = new SpringTemplateEngine();
    templateEngine.setTemplateResolver(templateResolver);

    ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
    viewResolver.setTemplateEngine(templateEngine);

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new SessionTaskChallengeController(
                    completeUseCase, sessions, recordLoginDevice, redirectUrlResolver))
            .setViewResolvers(viewResolver)
            .build();
  }

  private MockHttpSession pendingSessionFor(final AccountId accountId, final String factor) {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        SessionTaskPendingState.ACCOUNT_ID_ATTRIBUTE, accountId.value().toString());
    session.setAttribute(SessionTaskPendingState.FACTOR_ATTRIBUTE, factor);
    session.setAttribute(
        SessionTaskPendingState.ORGANIZATION_ID_ATTRIBUTE, ORGANIZATION_ID.toString());
    return session;
  }

  @Test
  void getWithNoSessionRedirectsToLogin() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/login/session-task/password-reset", ORGANIZATION_ID))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login"));
  }

  @Test
  void getWithAPendingTaskForADifferentOrganizationRedirectsToLoginBrOrg02() throws Exception {
    MockHttpSession session = pendingSessionFor(AccountId.newId(), "PASSWORD");

    mockMvc
        .perform(
            get("/o/{organizationId}/login/session-task/password-reset", UUID.randomUUID())
                .session(session))
        .andExpect(status().is3xxRedirection());
  }

  @Test
  void getWithAPendingTaskShowsTheForm() throws Exception {
    MockHttpSession session = pendingSessionFor(AccountId.newId(), "PASSWORD");

    mockMvc
        .perform(
            get("/o/{organizationId}/login/session-task/password-reset", ORGANIZATION_ID)
                .session(session))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/session-task-password-reset"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void postWithNoPendingTaskRedirectsToLoginWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login/session-task/password-reset", ORGANIZATION_ID)
                .param("newPassword", "a-Str0ng-Password!")
                .param("confirmPassword", "a-Str0ng-Password!"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login"));

    verifyNoInteractions(completeUseCase);
  }

  @Test
  void postWithAMismatchedConfirmationRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    MockHttpSession session = pendingSessionFor(AccountId.newId(), "PASSWORD");

    mockMvc
        .perform(
            post("/o/{organizationId}/login/session-task/password-reset", ORGANIZATION_ID)
                .session(session)
                .param("newPassword", "a-Str0ng-Password!")
                .param("confirmPassword", "does-not-match"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/session-task-password-reset"))
        .andExpect(model().attributeHasFieldErrors("form", "confirmPassword"));

    verifyNoInteractions(completeUseCase);
  }

  @Test
  void postWithAWeakPasswordRerendersTheFormWithAFieldError() throws Exception {
    MockHttpSession session = pendingSessionFor(AccountId.newId(), "PASSWORD");
    doThrow(new WeakPasswordException()).when(completeUseCase).handle(any());

    mockMvc
        .perform(
            post("/o/{organizationId}/login/session-task/password-reset", ORGANIZATION_ID)
                .session(session)
                .param("newPassword", "weak-Pass1!")
                .param("confirmPassword", "weak-Pass1!"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/session-task-password-reset"))
        .andExpect(model().attributeHasFieldErrors("form", "newPassword"));

    verify(sessions, never()).establish(any(), any(), any(), any());
  }

  @Test
  void postWithAValidPasswordAndAPasswordFactorEstablishesViaEstablish() throws Exception {
    AccountId accountId = AccountId.newId();
    MockHttpSession session = pendingSessionFor(accountId, "PASSWORD");
    when(sessions.establish(any(), any(), eq(accountId.value()), any()))
        .thenReturn("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc");

    mockMvc
        .perform(
            post("/o/{organizationId}/login/session-task/password-reset", ORGANIZATION_ID)
                .session(session)
                .param("newPassword", "a-Str0ng-Password!")
                .param("confirmPassword", "a-Str0ng-Password!"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc"));

    verify(sessions).establish(any(), any(), eq(accountId.value()), any());
    verify(sessions, never()).establishViaOneTimeEmailProof(any(), any(), any(), any());
    verify(recordLoginDevice).handle(any());
    assertPendingStateCleared(session);
  }

  @Test
  void postWithAValidPasswordAndAOneTimeEmailProofFactorEstablishesViaThatMethod()
      throws Exception {
    AccountId accountId = AccountId.newId();
    MockHttpSession session = pendingSessionFor(accountId, "ONE_TIME_EMAIL_PROOF");
    when(sessions.establishViaOneTimeEmailProof(any(), any(), eq(accountId.value()), any()))
        .thenReturn("/o/" + ORGANIZATION_ID + "/login?authenticated");

    mockMvc
        .perform(
            post("/o/{organizationId}/login/session-task/password-reset", ORGANIZATION_ID)
                .session(session)
                .param("newPassword", "a-Str0ng-Password!")
                .param("confirmPassword", "a-Str0ng-Password!"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login?authenticated"));

    verify(sessions).establishViaOneTimeEmailProof(any(), any(), eq(accountId.value()), any());
    verify(sessions, never()).establish(any(), any(), any(), any());
    assertPendingStateCleared(session);
  }

  private void assertPendingStateCleared(final MockHttpSession session) {
    org.assertj.core.api.Assertions.assertThat(
            session.getAttribute(SessionTaskPendingState.ACCOUNT_ID_ATTRIBUTE))
        .isNull();
    org.assertj.core.api.Assertions.assertThat(
            session.getAttribute(SessionTaskPendingState.FACTOR_ATTRIBUTE))
        .isNull();
    org.assertj.core.api.Assertions.assertThat(
            session.getAttribute(SessionTaskPendingState.ORGANIZATION_ID_ATTRIBUTE))
        .isNull();
  }
}

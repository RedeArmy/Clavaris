package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.chooseaccountusername.ChooseAccountUsernameCommand;
import com.clavaris.identity.application.usecase.chooseaccountusername.ChooseAccountUsernameUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventUseCase;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** ADR-0031. Same standalone MockMvc + real Thymeleaf setup as the forced-reset task's test. */
class SessionTaskUsernameControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final String PATH = "/o/{organizationId}/login/session-task/username";

  private ChooseAccountUsernameUseCase chooseUsername;
  private AccountAuthenticationPolicyProvider policyProvider;
  private AuthenticatedSessionEstablisher sessions;
  private RecordAccountLoginDeviceUseCase recordLoginDevice;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    chooseUsername = mock(ChooseAccountUsernameUseCase.class);
    policyProvider = mock(AccountAuthenticationPolicyProvider.class);
    sessions = mock(AuthenticatedSessionEstablisher.class);
    recordLoginDevice = mock(RecordAccountLoginDeviceUseCase.class);
    final RedirectUrlResolver redirectUrlResolver = mock(RedirectUrlResolver.class);
    when(recordLoginDevice.handle(any())).thenReturn(Optional.empty());
    when(redirectUrlResolver.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());
    when(policyProvider.policyFor(new OrganizationId(ORGANIZATION_ID)))
        .thenReturn(AccountAuthenticationPolicySnapshot.defaults());

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
                new SessionTaskUsernameController(
                    chooseUsername,
                    policyProvider,
                    sessions,
                    recordLoginDevice,
                    redirectUrlResolver,
                    mock(RecordLoginEventUseCase.class)))
            .setViewResolvers(viewResolver)
            .build();
  }

  private void requireUsername() {
    when(policyProvider.policyFor(new OrganizationId(ORGANIZATION_ID)))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                false,
                EmailVerificationMethod.LINK,
                false,
                false,
                true,
                false,
                true,
                false,
                false));
  }

  private MockHttpSession pendingSessionFor(final AccountId accountId, final String task) {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        SessionTaskPendingState.ACCOUNT_ID_ATTRIBUTE, accountId.value().toString());
    session.setAttribute(SessionTaskPendingState.FACTOR_ATTRIBUTE, "SOCIAL");
    session.setAttribute(SessionTaskPendingState.PROVIDER_ATTRIBUTE, SocialProvider.GOOGLE.name());
    session.setAttribute(
        SessionTaskPendingState.ORGANIZATION_ID_ATTRIBUTE, ORGANIZATION_ID.toString());
    if (task != null) {
      session.setAttribute(SessionTaskPendingState.TASK_ATTRIBUTE, task);
    }
    return session;
  }

  private MockHttpSession pendingUsernameTask(final AccountId accountId) {
    return pendingSessionFor(accountId, SessionTaskPendingState.TASK_USERNAME);
  }

  @Test
  void getWithNoSessionRedirectsToLogin() throws Exception {
    mockMvc
        .perform(get(PATH, ORGANIZATION_ID))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login"));
  }

  @Test
  void getForADifferentOrganizationRedirectsToLoginBrOrg02() throws Exception {
    mockMvc
        .perform(get(PATH, UUID.randomUUID()).session(pendingUsernameTask(AccountId.newId())))
        .andExpect(status().is3xxRedirection());
  }

  // The pending state of the forced password reset (or of a session paused before the marker
  // existed) must not open this page: each task's page only serves its own pause.
  @Test
  void getWithTheForcedPasswordResetPendingStateRedirectsToLogin() throws Exception {
    mockMvc
        .perform(
            get(PATH, ORGANIZATION_ID)
                .session(
                    pendingSessionFor(
                        AccountId.newId(), SessionTaskPendingState.TASK_PASSWORD_RESET)))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login"));
    mockMvc
        .perform(get(PATH, ORGANIZATION_ID).session(pendingSessionFor(AccountId.newId(), null)))
        .andExpect(status().is3xxRedirection());
  }

  @Test
  void getShowsTheFormWithASkipOptionWhenTheUsernameIsNotRequired() throws Exception {
    mockMvc
        .perform(get(PATH, ORGANIZATION_ID).session(pendingUsernameTask(AccountId.newId())))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/session-task-username"))
        .andExpect(model().attribute("usernameRequired", false))
        .andExpect(content().string(containsString("Skip for now")));
  }

  @Test
  void getShowsNoSkipOptionWhenTheOrganizationRequiresAUsername() throws Exception {
    requireUsername();

    final String html =
        mockMvc
            .perform(get(PATH, ORGANIZATION_ID).session(pendingUsernameTask(AccountId.newId())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).doesNotContain("Skip for now");
  }

  @Test
  void postWithNoPendingTaskRedirectsToLoginWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(post(PATH, ORGANIZATION_ID).param("username", "ada"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login"));

    verifyNoInteractions(chooseUsername);
  }

  @Test
  void postWithTheForcedResetPendingStateNeverReachesTheUseCase() throws Exception {
    mockMvc
        .perform(
            post(PATH, ORGANIZATION_ID)
                .session(
                    pendingSessionFor(
                        AccountId.newId(), SessionTaskPendingState.TASK_PASSWORD_RESET))
                .param("username", "ada"))
        .andExpect(status().is3xxRedirection());

    verifyNoInteractions(chooseUsername);
    verify(sessions, never())
        .establishViaSocialLogin(any(), any(), any(), any(), anyBoolean(), any());
  }

  @Test
  void postChoosesTheUsernameThenEstablishesTheSocialSessionAndClearsThePause() throws Exception {
    final AccountId accountId = AccountId.newId();
    final MockHttpSession session = pendingUsernameTask(accountId);
    when(sessions.establishViaSocialLogin(
            any(), any(), eq(accountId.value()), eq(SocialProvider.GOOGLE), eq(false), any()))
        .thenReturn("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc");

    mockMvc
        .perform(post(PATH, ORGANIZATION_ID).session(session).param("username", "ada_l"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc"));

    final ArgumentCaptor<ChooseAccountUsernameCommand> command =
        ArgumentCaptor.forClass(ChooseAccountUsernameCommand.class);
    verify(chooseUsername).handle(command.capture());
    assertThat(command.getValue().accountId()).isEqualTo(accountId);
    assertThat(command.getValue().rawUsername()).isEqualTo("ada_l");
    assertThat(session.getAttribute(SessionTaskPendingState.ACCOUNT_ID_ATTRIBUTE)).isNull();
    assertThat(session.getAttribute(SessionTaskPendingState.TASK_ATTRIBUTE)).isNull();
  }

  @Test
  void postWithATakenUsernameRerendersTheFormWithAFieldErrorAndEstablishesNothing()
      throws Exception {
    doThrow(new UsernameAlreadyRegisteredException(new OrganizationId(ORGANIZATION_ID)))
        .when(chooseUsername)
        .handle(any());

    mockMvc
        .perform(
            post(PATH, ORGANIZATION_ID)
                .session(pendingUsernameTask(AccountId.newId()))
                .param("username", "taken"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/session-task-username"))
        .andExpect(model().attributeHasFieldErrors("form", "username"))
        .andExpect(content().string(containsString("This username is already taken")));

    verify(sessions, never())
        .establishViaSocialLogin(any(), any(), any(), any(), anyBoolean(), any());
  }

  @Test
  void postWithAnInvalidShapeRerendersTheFormWithAFieldError() throws Exception {
    doThrow(new IllegalArgumentException("Not a valid username"))
        .when(chooseUsername)
        .handle(any());

    mockMvc
        .perform(
            post(PATH, ORGANIZATION_ID)
                .session(pendingUsernameTask(AccountId.newId()))
                .param("username", "no spaces"))
        .andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("form", "username"))
        .andExpect(content().string(containsString("Username must be 3-32 characters")));
  }

  @Test
  void skipEstablishesTheSessionWithoutChoosingAnythingWhenTheUsernameIsOptional()
      throws Exception {
    final AccountId accountId = AccountId.newId();
    when(sessions.establishViaSocialLogin(
            any(), any(), eq(accountId.value()), eq(SocialProvider.GOOGLE), eq(false), any()))
        .thenReturn("/o/" + ORGANIZATION_ID + "/login?authenticated");

    mockMvc
        .perform(post(PATH + "/skip", ORGANIZATION_ID).session(pendingUsernameTask(accountId)))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login?authenticated"));

    verifyNoInteractions(chooseUsername);
  }

  @Test
  void skipIsRefusedWhenTheOrganizationRequiresAUsername() throws Exception {
    requireUsername();

    mockMvc
        .perform(
            post(PATH + "/skip", ORGANIZATION_ID).session(pendingUsernameTask(AccountId.newId())))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login/session-task/username"));

    verify(sessions, never())
        .establishViaSocialLogin(any(), any(), any(), any(), anyBoolean(), any());
  }
}

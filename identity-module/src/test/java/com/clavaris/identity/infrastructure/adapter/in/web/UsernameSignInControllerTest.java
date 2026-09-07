package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import com.clavaris.identity.application.usecase.authenticatewithpassword.EmailNotVerifiedException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.InvalidCredentialsException;
import com.clavaris.identity.application.usecase.authenticatewithusername.AuthenticateWithUsernameCommand;
import com.clavaris.identity.application.usecase.authenticatewithusername.AuthenticateWithUsernameUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc + real Thymeleaf setup as {@link LoginControllerTest}. */
class UsernameSignInControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private AuthenticateWithUsernameUseCase useCase;
  private AuthenticatedSessionEstablisher sessions;
  private RecordAccountLoginDeviceUseCase recordLoginDevice;
  private KnownDeviceRepository knownDevices;
  private AccountAuthenticationPolicyProvider authenticationPolicyProvider;
  private RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge;
  private RedirectUrlResolver redirectUrlResolver;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(AuthenticateWithUsernameUseCase.class);
    sessions = mock(AuthenticatedSessionEstablisher.class);
    recordLoginDevice = mock(RecordAccountLoginDeviceUseCase.class);
    knownDevices = mock(KnownDeviceRepository.class);
    authenticationPolicyProvider = mock(AccountAuthenticationPolicyProvider.class);
    requestDeviceTrustChallenge = mock(RequestDeviceTrustChallengeUseCase.class);
    redirectUrlResolver = mock(RedirectUrlResolver.class);
    when(authenticationPolicyProvider.policyFor(any()))
        .thenReturn(AccountAuthenticationPolicySnapshot.defaults());
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
                new UsernameSignInController(
                    useCase,
                    sessions,
                    recordLoginDevice,
                    knownDevices,
                    authenticationPolicyProvider,
                    requestDeviceTrustChallenge,
                    redirectUrlResolver))
            .setViewResolvers(viewResolver)
            .build();
  }

  // TD-PERF-015: same rationale as LoginControllerTest's own identical factory.
  private Account newAccount() {
    Account account =
        Account.register(new OrganizationId(ORGANIZATION_ID), new Email("user@example.com"));
    account.attachPasswordCredential("argon2id$hashed");
    return account;
  }

  @Test
  void getShowsTheLoginForm() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/login/username", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-username"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void validCredentialsEstablishASessionAndRedirectToWhatItReturns() throws Exception {
    Account account = newAccount();
    when(useCase.handle(any())).thenReturn(account);
    when(sessions.establish(any(), any(), eq(account.id().value()), any()))
        .thenReturn("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc");

    mockMvc
        .perform(
            post("/o/{organizationId}/login/username", ORGANIZATION_ID)
                .param("username", "flowuser")
                .param("password", "correct-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc"));

    verify(useCase)
        .handle(
            new AuthenticateWithUsernameCommand(
                new OrganizationId(ORGANIZATION_ID), new Username("flowuser"), "correct-password"));
    verify(recordLoginDevice).handle(any());
  }

  @Test
  void blankUsernameRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login/username", ORGANIZATION_ID)
                .param("username", "")
                .param("password", "correct-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-username"))
        .andExpect(model().attributeHasFieldErrors("form", "username"));

    verifyNoInteractions(useCase);
  }

  @Test
  void invalidCredentialsRerenderTheFormWithAGenericErrorNeverAFieldError() throws Exception {
    when(useCase.handle(any())).thenThrow(new InvalidCredentialsException());

    mockMvc
        .perform(
            post("/o/{organizationId}/login/username", ORGANIZATION_ID)
                .param("username", "flowuser")
                .param("password", "wrong-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-username"))
        .andExpect(model().attribute("loginError", true));

    verify(sessions, never()).establish(any(), any(), any(), any());
  }

  @Test
  void malformedUsernameRerendersTheFormWithTheSameGenericError() throws Exception {
    // A shape the form's plain size/blank check wouldn't catch, such as uppercase letters or an
    // embedded space, but Username's own domain constructor rejects — same anti-enumeration-generic
    // outcome as an actual InvalidCredentialsException, not a distinguishable field error.
    mockMvc
        .perform(
            post("/o/{organizationId}/login/username", ORGANIZATION_ID)
                .param("username", "Not A Valid Username")
                .param("password", "some-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-username"))
        .andExpect(model().attribute("loginError", true));

    verifyNoInteractions(sessions);
  }

  @Test
  void emailNotVerifiedRerendersTheFormWithTheSpecificError() throws Exception {
    when(useCase.handle(any())).thenThrow(new EmailNotVerifiedException());

    mockMvc
        .perform(
            post("/o/{organizationId}/login/username", ORGANIZATION_ID)
                .param("username", "flowuser")
                .param("password", "correct-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-username"))
        .andExpect(model().attribute("emailNotVerifiedError", true));

    verify(sessions, never()).establish(any(), any(), any(), any());
  }

  @Test
  void pausesForDeviceTrustWhenTheOrganizationRequiresIt() throws Exception {
    Account account = newAccount();
    when(useCase.handle(any())).thenReturn(account);
    when(authenticationPolicyProvider.policyFor(any()))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                false, EmailVerificationMethod.LINK, false, false, true, false, true, true, true));
    when(knownDevices.findByAccountIdAndDeviceTokenHash(any(), any())).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post("/o/{organizationId}/login/username", ORGANIZATION_ID)
                .param("username", "flowuser")
                .param("password", "correct-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login/device-trust"));

    verify(sessions, never()).establish(any(), any(), any(), any());
    verify(requestDeviceTrustChallenge).handle(any());
  }
}

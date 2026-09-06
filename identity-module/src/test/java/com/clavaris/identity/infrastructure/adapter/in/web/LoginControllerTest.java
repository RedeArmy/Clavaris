package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordCommand;
import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordUseCase;
import com.clavaris.identity.application.usecase.authenticatewithpassword.EmailNotVerifiedException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.InvalidCredentialsException;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingSnapshot;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectAction;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.EnumSet;
import java.util.List;
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

/**
 * Same standalone MockMvc + real Thymeleaf setup as {@link RegisterAccountControllerTest} — see its
 * Javadoc for why.
 */
class LoginControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private AuthenticateWithPasswordUseCase useCase;
  private AuthenticatedSessionEstablisher sessionEstablisher;
  private OrganizationSocialLoginPolicyProvider policyProvider;
  private RecordAccountLoginDeviceUseCase recordLoginDevice;
  private KnownDeviceRepository knownDevices;
  private AccountAuthenticationPolicyProvider authenticationPolicyProvider;
  private RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge;
  private RedirectUrlResolver redirectUrlResolver;
  private AccountRepository accounts;
  private ClientBrandingProvider clientBrandingProvider;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(AuthenticateWithPasswordUseCase.class);
    sessionEstablisher = mock(AuthenticatedSessionEstablisher.class);
    policyProvider = mock(OrganizationSocialLoginPolicyProvider.class);
    recordLoginDevice = mock(RecordAccountLoginDeviceUseCase.class);
    knownDevices = mock(KnownDeviceRepository.class);
    authenticationPolicyProvider = mock(AccountAuthenticationPolicyProvider.class);
    requestDeviceTrustChallenge = mock(RequestDeviceTrustChallengeUseCase.class);
    redirectUrlResolver = mock(RedirectUrlResolver.class);
    accounts = mock(AccountRepository.class);
    clientBrandingProvider = mock(ClientBrandingProvider.class);
    // Matches today's real default (no redirect policy configured) — every existing test below
    // predates this feature and expects the controller's own hardcoded literal fallback.
    when(redirectUrlResolver.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());
    // Matches today's real default (no branding configured) — every existing test below predates
    // ADR-0009 §3 and expects the template's own unbranded default look.
    when(clientBrandingProvider.brandingFor(any(), any()))
        .thenReturn(ClientBrandingSnapshot.unconfigured());
    // Matches today's real default (no session task ever forced) — SessionTaskGate treats an
    // absent account exactly like one with no outstanding requirement, same as a real empty
    // Optional<Instant> would.
    when(accounts.findById(any())).thenReturn(Optional.empty());
    // Matches today's real default (ADR-0024) — every existing test below predates this policy.
    when(authenticationPolicyProvider.policyFor(new OrganizationId(ORGANIZATION_ID)))
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
                new LoginController(
                    useCase,
                    sessionEstablisher,
                    policyProvider,
                    recordLoginDevice,
                    knownDevices,
                    authenticationPolicyProvider,
                    requestDeviceTrustChallenge,
                    redirectUrlResolver,
                    accounts,
                    clientBrandingProvider))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getShowsTheLoginFormWithNoSocialButtonsWhenNoneAreEnabled() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/login", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login"))
        .andExpect(model().attributeExists("form"))
        .andExpect(model().attribute("socialProviders", List.of()));
  }

  @Test
  void getShowsOnlyTheProvidersTheOrganizationHasEnabled() throws Exception {
    // Code review finding (TD-SEC-032, closed): the controller now calls allowedProviders() once
    // per render, not isProviderAllowed() once per known SocialProvider.
    when(policyProvider.allowedProviders(new OrganizationId(ORGANIZATION_ID)))
        .thenReturn(EnumSet.of(SocialProvider.GOOGLE));

    mockMvc
        .perform(get("/o/{organizationId}/login", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(model().attribute("socialProviders", List.of(SocialProvider.GOOGLE)));
  }

  @Test
  void validCredentialsEstablishASessionAndRedirectToWhatItReturns() throws Exception {
    AccountId accountId = AccountId.newId();
    when(useCase.handle(any())).thenReturn(accountId);
    when(sessionEstablisher.establish(any(), any(), eq(accountId.value()), anyString()))
        .thenReturn("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc");

    mockMvc
        .perform(
            post("/o/{organizationId}/login", ORGANIZATION_ID)
                .param("email", "user@example.com")
                .param("password", "correct-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc"));

    verify(useCase)
        .handle(
            new AuthenticateWithPasswordCommand(
                new OrganizationId(ORGANIZATION_ID),
                new Email("user@example.com"),
                "correct-password"));
    // New-device login email notification — fired after a successful login, same accountId.
    verify(recordLoginDevice).handle(any());
  }

  // Clerk "session tasks" parity.
  @Test
  void pausesForAForcedPasswordResetWhenTheAccountRequiresOne() throws Exception {
    AccountId accountId = AccountId.newId();
    when(useCase.handle(any())).thenReturn(accountId);
    Account accountRequiringReset =
        Account.register(new OrganizationId(ORGANIZATION_ID), new Email("user@example.com"));
    accountRequiringReset.attachPasswordCredential("argon2id$hashed");
    accountRequiringReset.requirePasswordReset();
    when(accounts.findById(accountId)).thenReturn(Optional.of(accountRequiringReset));

    mockMvc
        .perform(
            post("/o/{organizationId}/login", ORGANIZATION_ID)
                .param("email", "user@example.com")
                .param("password", "correct-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login/session-task/password-reset"));

    verify(sessionEstablisher, never()).establish(any(), any(), any(), any());
    verifyNoInteractions(recordLoginDevice);
  }

  // Clerk "customize redirect URLs" parity: when the resolver has an answer, it becomes the
  // fallbackUrl argument passed to AuthenticatedSessionEstablisher — the establisher itself still
  // decides whether a SavedRequest overrides it (not exercised here, sessionEstablisher is
  // mocked), see RedirectUrlResolver's own Javadoc for the full precedence chain.
  @Test
  void aResolvedRedirectPolicyBecomesTheFallbackUrlPassedToTheSessionEstablisher()
      throws Exception {
    AccountId accountId = AccountId.newId();
    when(useCase.handle(any())).thenReturn(accountId);
    when(redirectUrlResolver.resolve(
            new OrganizationId(ORGANIZATION_ID), "test_client", null, RedirectAction.SIGN_IN))
        .thenReturn(Optional.of("https://app.example.com/dashboard"));
    when(sessionEstablisher.establish(
            any(), any(), eq(accountId.value()), eq("https://app.example.com/dashboard")))
        .thenReturn("https://app.example.com/dashboard");

    mockMvc
        .perform(
            post("/o/{organizationId}/login", ORGANIZATION_ID)
                .param("email", "user@example.com")
                .param("password", "correct-password")
                .param("clientId", "test_client"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("https://app.example.com/dashboard"));

    verify(sessionEstablisher)
        .establish(any(), any(), eq(accountId.value()), eq("https://app.example.com/dashboard"));
  }

  @Test
  void invalidEmailRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login", ORGANIZATION_ID)
                .param("email", "not-an-email")
                .param("password", "some-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(useCase);
    verifyNoInteractions(sessionEstablisher);
    verifyNoInteractions(recordLoginDevice);
  }

  @Test
  void blankPasswordRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login", ORGANIZATION_ID)
                .param("email", "user@example.com")
                .param("password", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login"))
        .andExpect(model().attributeHasFieldErrors("form", "password"));

    verifyNoInteractions(useCase);
  }

  @Test
  void wrongCredentialsRerenderTheFormWithAGenericErrorNeverAFieldError() throws Exception {
    when(useCase.handle(any())).thenThrow(new InvalidCredentialsException());

    mockMvc
        .perform(
            post("/o/{organizationId}/login", ORGANIZATION_ID)
                .param("email", "user@example.com")
                .param("password", "wrong-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login"))
        .andExpect(model().attribute("loginError", true))
        // Deliberately never a field-level error — see LoginController's own comment on why a
        // field-scoped error would itself leak which field was the actual problem.
        .andExpect(model().attributeHasNoErrors("form"));

    verify(sessionEstablisher, never()).establish(any(), any(), any(), anyString());
    verifyNoInteractions(recordLoginDevice);
  }

  @Test
  void anUnverifiedEmailRerendersTheFormWithItsOwnDistinctError() throws Exception {
    // ADR-0024 §2: a distinct, more specific message than the generic loginError above — see
    // EmailNotVerifiedException's own Javadoc for why this one case is allowed to differ from the
    // anti-enumeration-generic rejection every other failure mode uses.
    when(useCase.handle(any())).thenThrow(new EmailNotVerifiedException());

    mockMvc
        .perform(
            post("/o/{organizationId}/login", ORGANIZATION_ID)
                .param("email", "user@example.com")
                .param("password", "correct-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login"))
        .andExpect(model().attribute("emailNotVerifiedError", true))
        .andExpect(model().attributeHasNoErrors("form"));

    verify(sessionEstablisher, never()).establish(any(), any(), any(), anyString());
    verifyNoInteractions(recordLoginDevice);
  }
}

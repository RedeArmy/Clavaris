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

import com.clavaris.identity.application.usecase.authenticatewithemailcode.AuthenticateWithEmailCodeCommand;
import com.clavaris.identity.application.usecase.authenticatewithemailcode.AuthenticateWithEmailCodeUseCase;
import com.clavaris.identity.application.usecase.authenticatewithemailcode.InvalidOneTimeCodeException;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailsignincode.RequestEmailSignInCodeCommand;
import com.clavaris.identity.application.usecase.requestemailsignincode.RequestEmailSignInCodeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectAction;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
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
class EmailCodeSignInControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private RequestEmailSignInCodeUseCase requestUseCase;
  private AuthenticateWithEmailCodeUseCase authenticateUseCase;
  private AuthenticatedSessionEstablisher sessions;
  private RecordAccountLoginDeviceUseCase recordLoginDevice;
  private KnownDeviceRepository knownDevices;
  private AccountAuthenticationPolicyProvider authenticationPolicyProvider;
  private RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge;
  private RedirectUrlResolver redirectUrlResolver;
  private AccountRepository accounts;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    requestUseCase = mock(RequestEmailSignInCodeUseCase.class);
    authenticateUseCase = mock(AuthenticateWithEmailCodeUseCase.class);
    sessions = mock(AuthenticatedSessionEstablisher.class);
    recordLoginDevice = mock(RecordAccountLoginDeviceUseCase.class);
    knownDevices = mock(KnownDeviceRepository.class);
    authenticationPolicyProvider = mock(AccountAuthenticationPolicyProvider.class);
    requestDeviceTrustChallenge = mock(RequestDeviceTrustChallengeUseCase.class);
    redirectUrlResolver = mock(RedirectUrlResolver.class);
    accounts = mock(AccountRepository.class);
    when(authenticationPolicyProvider.policyFor(any()))
        .thenReturn(AccountAuthenticationPolicySnapshot.defaults());
    when(recordLoginDevice.handle(any())).thenReturn(Optional.empty());
    when(redirectUrlResolver.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());
    when(accounts.findById(any())).thenReturn(Optional.empty());

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
                new EmailCodeSignInController(
                    requestUseCase,
                    authenticateUseCase,
                    sessions,
                    recordLoginDevice,
                    knownDevices,
                    authenticationPolicyProvider,
                    requestDeviceTrustChallenge,
                    redirectUrlResolver,
                    accounts))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getShowsTheRequestForm() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/login/email-code", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-email-code-request"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void postWithAValidEmailAlwaysRedirectsToConfirmAntiEnumeration() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-code", ORGANIZATION_ID)
                .param("email", "someone@example.com"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl(
                "/o/" + ORGANIZATION_ID + "/login/email-code/confirm?email=someone@example.com"));

    verify(requestUseCase).handle(any(RequestEmailSignInCodeCommand.class));
  }

  // Clerk "customize redirect URLs" parity: this hop is a genuine cross-URL redirect, so
  // clientId/redirectUrl must be explicitly carried onto it (RedirectQueryParams), not left to
  // the browser's own query-string preservation (which only applies to same-URL resubmits).
  @Test
  void postCarriesClientIdAndRedirectUrlOntoTheConfirmRedirect() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-code", ORGANIZATION_ID)
                .param("email", "someone@example.com")
                .param("clientId", "test_client")
                .param("redirectUrl", "https://app.example.com/callback"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl(
                "/o/"
                    + ORGANIZATION_ID
                    + "/login/email-code/confirm?email=someone@example.com"
                    + "&clientId=test_client&redirectUrl=https%3A%2F%2Fapp.example.com%2Fcallback"));
  }

  @Test
  void postWithAnInvalidEmailRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-code", ORGANIZATION_ID)
                .param("email", "not-an-email"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-email-code-request"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(requestUseCase);
  }

  @Test
  void getConfirmShowsTheConfirmFormWithTheEmailCarriedAsAHiddenField() throws Exception {
    mockMvc
        .perform(
            get("/o/{organizationId}/login/email-code/confirm", ORGANIZATION_ID)
                .param("email", "someone@example.com"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-email-code-confirm"))
        .andExpect(
            model()
                .attribute(
                    "form",
                    org.hamcrest.Matchers.hasProperty(
                        "email", org.hamcrest.Matchers.is("someone@example.com"))));
  }

  @Test
  void postConfirmWithAValidCodeEstablishesASessionAndRedirectsToWhatItReturns() throws Exception {
    AccountId accountId = AccountId.newId();
    when(authenticateUseCase.handle(any())).thenReturn(accountId);
    when(sessions.establishViaOneTimeEmailProof(any(), any(), eq(accountId.value()), any()))
        .thenReturn("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc");

    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-code/confirm", ORGANIZATION_ID)
                .param("email", "someone@example.com")
                .param("code", "123456"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc"));

    verify(authenticateUseCase)
        .handle(
            new AuthenticateWithEmailCodeCommand(
                new OrganizationId(ORGANIZATION_ID),
                new com.clavaris.identity.domain.model.Email("someone@example.com"),
                "123456"));
    verify(recordLoginDevice).handle(any());
  }

  // Clerk "customize redirect URLs" parity: a resolved policy becomes the fallbackUrl passed to
  // the establisher — same wiring LoginControllerTest's own identical test proves.
  @Test
  void aResolvedRedirectPolicyBecomesTheFallbackUrlOnConfirm() throws Exception {
    AccountId accountId = AccountId.newId();
    when(authenticateUseCase.handle(any())).thenReturn(accountId);
    when(redirectUrlResolver.resolve(
            new OrganizationId(ORGANIZATION_ID), "test_client", null, RedirectAction.SIGN_IN))
        .thenReturn(Optional.of("https://app.example.com/dashboard"));
    when(sessions.establishViaOneTimeEmailProof(
            any(), any(), eq(accountId.value()), eq("https://app.example.com/dashboard")))
        .thenReturn("https://app.example.com/dashboard");

    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-code/confirm", ORGANIZATION_ID)
                .param("email", "someone@example.com")
                .param("code", "123456")
                .param("clientId", "test_client"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("https://app.example.com/dashboard"));
  }

  @Test
  void postConfirmWithAnInvalidCodeRerendersTheFormWithACodeError() throws Exception {
    when(authenticateUseCase.handle(any())).thenThrow(new InvalidOneTimeCodeException());

    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-code/confirm", ORGANIZATION_ID)
                .param("email", "someone@example.com")
                .param("code", "000000"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-email-code-confirm"))
        .andExpect(model().attribute("codeError", true));

    verify(sessions, never()).establishViaOneTimeEmailProof(any(), any(), any(), any());
  }

  @Test
  void postConfirmPausesForDeviceTrustWhenTheOrganizationRequiresIt() throws Exception {
    AccountId accountId = AccountId.newId();
    when(authenticateUseCase.handle(any())).thenReturn(accountId);
    when(authenticationPolicyProvider.policyFor(any()))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                false, EmailVerificationMethod.LINK, true, false, false, false, false, true, true));
    when(knownDevices.findByAccountIdAndDeviceTokenHash(any(), any())).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-code/confirm", ORGANIZATION_ID)
                .param("email", "someone@example.com")
                .param("code", "123456"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login/device-trust"));

    verify(sessions, never()).establishViaOneTimeEmailProof(any(), any(), any(), any());
    verify(requestDeviceTrustChallenge).handle(any());
  }
}

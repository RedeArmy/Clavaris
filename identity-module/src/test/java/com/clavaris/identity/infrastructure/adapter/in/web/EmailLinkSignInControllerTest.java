package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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

import com.clavaris.identity.application.usecase.authenticatewithemaillink.AuthenticateWithEmailLinkCommand;
import com.clavaris.identity.application.usecase.authenticatewithemaillink.AuthenticateWithEmailLinkUseCase;
import com.clavaris.identity.application.usecase.authenticatewithemaillink.InvalidSignInLinkException;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventUseCase;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailsigninlink.RequestEmailSignInLinkCommand;
import com.clavaris.identity.application.usecase.requestemailsigninlink.RequestEmailSignInLinkUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.application.usecase.requiresecondfactor.RequiresSecondFactorUseCase;
import com.clavaris.identity.domain.model.Account;
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
class EmailLinkSignInControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private RequestEmailSignInLinkUseCase requestUseCase;
  private AuthenticateWithEmailLinkUseCase authenticateUseCase;
  private AuthenticatedSessionEstablisher sessions;
  private RecordAccountLoginDeviceUseCase recordLoginDevice;
  private RecordLoginEventUseCase recordLoginEvent;
  private KnownDeviceRepository knownDevices;
  private AccountAuthenticationPolicyProvider authenticationPolicyProvider;
  private RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge;
  private PasskeyOffer passkeyOffer;
  private RequiresSecondFactorUseCase requiresSecondFactor;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    requiresSecondFactor = mock(RequiresSecondFactorUseCase.class);
    requestUseCase = mock(RequestEmailSignInLinkUseCase.class);
    authenticateUseCase = mock(AuthenticateWithEmailLinkUseCase.class);
    sessions = mock(AuthenticatedSessionEstablisher.class);
    recordLoginDevice = mock(RecordAccountLoginDeviceUseCase.class);
    recordLoginEvent = mock(RecordLoginEventUseCase.class);
    knownDevices = mock(KnownDeviceRepository.class);
    authenticationPolicyProvider = mock(AccountAuthenticationPolicyProvider.class);
    requestDeviceTrustChallenge = mock(RequestDeviceTrustChallengeUseCase.class);
    when(authenticationPolicyProvider.policyFor(any()))
        .thenReturn(AccountAuthenticationPolicySnapshot.defaults());
    when(recordLoginDevice.handle(any())).thenReturn(Optional.empty());

    passkeyOffer = mock(PasskeyOffer.class);

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
                new EmailLinkSignInController(
                    requestUseCase,
                    authenticateUseCase,
                    sessions,
                    recordLoginDevice,
                    knownDevices,
                    authenticationPolicyProvider,
                    requestDeviceTrustChallenge,
                    recordLoginEvent,
                    passkeyOffer,
                    requiresSecondFactor))
            .setViewResolvers(viewResolver)
            .build();
  }

  // TD-PERF-015: same rationale as LoginControllerTest's own identical factory.
  private Account newAccount() {
    return Account.register(
        new com.clavaris.identity.domain.model.OrganizationId(ORGANIZATION_ID),
        new com.clavaris.identity.domain.model.Email("someone@example.com"));
  }

  @Test
  void getShowsTheRequestForm() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/login/email-link", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-email-link-request"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void postWithAValidEmailAlwaysRedirectsToPendingAntiEnumeration() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-link", ORGANIZATION_ID)
                .param("email", "someone@example.com"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login/email-link/pending"));

    verify(requestUseCase).handle(any(RequestEmailSignInLinkCommand.class));
  }

  @Test
  void postWithAnInvalidEmailRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-link", ORGANIZATION_ID)
                .param("email", "not-an-email"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-email-link-request"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(requestUseCase);
  }

  @Test
  void getShowsThePendingPage() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/login/email-link/pending", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-email-link-pending"));
  }

  @Test
  void getConfirmShowsTheConfirmFormWithTheTokenCarriedAsAHiddenField() throws Exception {
    mockMvc
        .perform(
            get("/o/{organizationId}/login/email-link/confirm", ORGANIZATION_ID)
                .param("token", "a-real-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login-email-link-confirm"))
        .andExpect(
            model()
                .attribute(
                    "form",
                    org.hamcrest.Matchers.hasProperty(
                        "token", org.hamcrest.Matchers.is("a-real-token"))));
  }

  @Test
  void postConfirmGoesToThePasskeyOfferWhenOneApplies() throws Exception {
    Account account = newAccount();
    when(authenticateUseCase.handle(any())).thenReturn(account);
    when(sessions.establishViaOneTimeEmailProof(any(), any(), any(), eq(false), any()))
        .thenReturn("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc");
    when(passkeyOffer.interceptAfterEmailedProof(
            any(),
            eq(ORGANIZATION_ID),
            eq(account.id()),
            eq("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc")))
        .thenReturn(java.util.Optional.of("/o/" + ORGANIZATION_ID + "/account/passkey-offer"));

    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-link/confirm", ORGANIZATION_ID)
                .param("token", "a-real-token"))
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/account/passkey-offer"));
  }

  @Test
  void postConfirmWithAValidTokenEstablishesASessionAndRedirectsToWhatItReturns() throws Exception {
    Account account = newAccount();
    when(authenticateUseCase.handle(any())).thenReturn(account);
    when(sessions.establishViaOneTimeEmailProof(
            any(),
            any(),
            org.mockito.ArgumentMatchers.eq(account.id().value()),
            org.mockito.ArgumentMatchers.eq(false),
            any()))
        .thenReturn("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc");

    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-link/confirm", ORGANIZATION_ID)
                .param("token", "a-real-token"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc"));

    verify(authenticateUseCase)
        .handle(
            new AuthenticateWithEmailLinkCommand(
                new com.clavaris.identity.domain.model.OrganizationId(ORGANIZATION_ID),
                "a-real-token"));
    verify(recordLoginDevice).handle(any());
  }

  @Test
  void postConfirmWithABlankTokenRendersTheInvalidLinkPageWithoutCallingTheUseCase()
      throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-link/confirm", ORGANIZATION_ID)
                .param("token", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/verification-link-invalid"));

    verifyNoInteractions(authenticateUseCase);
  }

  @Test
  void postConfirmWithAnInvalidTokenRendersTheInvalidLinkPage() throws Exception {
    when(authenticateUseCase.handle(any())).thenThrow(new InvalidSignInLinkException());

    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-link/confirm", ORGANIZATION_ID)
                .param("token", "a-stale-or-forged-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/verification-link-invalid"));

    verify(sessions, never())
        .establishViaOneTimeEmailProof(any(), any(), any(), anyBoolean(), any());
  }

  // ADR-0038 (TD-ARCH-044): an emailed link is the proof of the mailbox too, so no second code is
  // mailed to it for a new device.
  @Test
  void postConfirmDoesNotPauseForDeviceTrustAfterAnEmailedLink() throws Exception {
    Account account = newAccount();
    when(authenticateUseCase.handle(any())).thenReturn(account);
    when(authenticationPolicyProvider.policyFor(any()))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                false, EmailVerificationMethod.LINK, false, true, false, false, true, true, false));
    when(knownDevices.findByAccountIdAndDeviceTokenHash(any(), any())).thenReturn(Optional.empty());
    when(sessions.establishViaOneTimeEmailProof(
            any(), any(), eq(account.id().value()), eq(false), any()))
        .thenReturn("/o/" + ORGANIZATION_ID + "/login?authenticated");

    mockMvc
        .perform(
            post("/o/{organizationId}/login/email-link/confirm", ORGANIZATION_ID)
                .param("token", "a-real-token"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login?authenticated"));

    verify(requestDeviceTrustChallenge, never()).handle(any());
  }
}

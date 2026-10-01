package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.identity.application.usecase.authenticatewithwebauthn.AuthenticateWithWebAuthnUseCase;
import com.clavaris.identity.application.usecase.authenticatewithwebauthn.InvalidWebAuthnAssertionException;
import com.clavaris.identity.application.usecase.authenticatewithwebauthn.StartWebAuthnAuthenticationUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventUseCase;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebAuthnSignInControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private StartWebAuthnAuthenticationUseCase startAuthentication;
  private AuthenticateWithWebAuthnUseCase authenticate;
  private KnownDeviceRepository knownDevices;
  private RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge;
  private AccountAuthenticationPolicyProvider authenticationPolicyProvider;
  private AuthenticatedSessionEstablisher sessions;
  private RecordAccountLoginDeviceUseCase recordLoginDevice;
  private RedirectUrlResolver redirectUrlResolver;
  private RecordLoginEventUseCase recordLoginEvent;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    startAuthentication = mock(StartWebAuthnAuthenticationUseCase.class);
    authenticate = mock(AuthenticateWithWebAuthnUseCase.class);
    knownDevices = mock(KnownDeviceRepository.class);
    requestDeviceTrustChallenge = mock(RequestDeviceTrustChallengeUseCase.class);
    authenticationPolicyProvider = mock(AccountAuthenticationPolicyProvider.class);
    sessions = mock(AuthenticatedSessionEstablisher.class);
    recordLoginDevice = mock(RecordAccountLoginDeviceUseCase.class);
    redirectUrlResolver = mock(RedirectUrlResolver.class);
    recordLoginEvent = mock(RecordLoginEventUseCase.class);

    when(authenticationPolicyProvider.policyFor(any()))
        .thenReturn(AccountAuthenticationPolicySnapshot.defaults());
    when(recordLoginDevice.handle(any())).thenReturn(Optional.empty());
    when(redirectUrlResolver.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new WebAuthnSignInController(
                    startAuthentication,
                    authenticate,
                    knownDevices,
                    requestDeviceTrustChallenge,
                    authenticationPolicyProvider,
                    sessions,
                    recordLoginDevice,
                    redirectUrlResolver,
                    recordLoginEvent))
            .build();
  }

  private Account newAccount() {
    return Account.register(new OrganizationId(ORGANIZATION_ID), new Email("user@example.com"));
  }

  // The controller's own /finish round-trips this through the REAL AssertionRequest.fromJson(...)
  // static deserializer — a fabricated JSON string would fail that parse, so this must be a
  // genuinely valid serialization, not just any string.
  private static String realAssertionRequestJson() throws Exception {
    PublicKeyCredentialRequestOptions options =
        PublicKeyCredentialRequestOptions.builder()
            .challenge(new ByteArray(new byte[] {1, 2, 3}))
            .build();
    return AssertionRequest.builder().publicKeyCredentialRequestOptions(options).build().toJson();
  }

  @Test
  void startReturnsTheCredentialsGetJsonAndStashesTheRequestInSession() throws Exception {
    AssertionRequest assertionRequest = mock(AssertionRequest.class);
    when(assertionRequest.toJson()).thenReturn(realAssertionRequestJson());
    when(assertionRequest.toCredentialsGetJson()).thenReturn("{\"publicKey\":{}}");
    when(startAuthentication.handle()).thenReturn(assertionRequest);

    mockMvc
        .perform(post("/o/{organizationId}/login/webauthn/start", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"publicKey\":{}}"));
  }

  @Test
  void finishWithoutAPendingChallengeReturnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login/webauthn/finish", ORGANIZATION_ID)
                .contentType("application/json")
                .content("{\"credential\":\"irrelevant\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  void finishWithAnInvalidAssertionReturnsBadRequestAndNeverEstablishesASession() throws Exception {
    AssertionRequest assertionRequest = mock(AssertionRequest.class);
    when(assertionRequest.toJson()).thenReturn(realAssertionRequestJson());
    when(assertionRequest.toCredentialsGetJson()).thenReturn("{\"publicKey\":{}}");
    when(startAuthentication.handle()).thenReturn(assertionRequest);
    when(authenticate.handle(any())).thenThrow(new InvalidWebAuthnAssertionException());

    MvcResult startResult =
        mockMvc
            .perform(post("/o/{organizationId}/login/webauthn/start", ORGANIZATION_ID))
            .andReturn();

    mockMvc
        .perform(
            post("/o/{organizationId}/login/webauthn/finish", ORGANIZATION_ID)
                .session((MockHttpSession) startResult.getRequest().getSession())
                .contentType("application/json")
                .content("{\"credential\":\"irrelevant\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    verifyNoInteractions(sessions);
  }

  @Test
  void finishOnSuccessReturnsTheRedirectTargetAndRecordsTheLoginDeviceAndEvent() throws Exception {
    AssertionRequest assertionRequest = mock(AssertionRequest.class);
    when(assertionRequest.toJson()).thenReturn(realAssertionRequestJson());
    when(assertionRequest.toCredentialsGetJson()).thenReturn("{\"publicKey\":{}}");
    when(startAuthentication.handle()).thenReturn(assertionRequest);

    Account account = newAccount();
    when(authenticate.handle(any())).thenReturn(account);
    when(sessions.establishViaPasskey(any(), any(), any(), anyBoolean(), any()))
        .thenReturn("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc");

    MvcResult startResult =
        mockMvc
            .perform(post("/o/{organizationId}/login/webauthn/start", ORGANIZATION_ID))
            .andReturn();

    mockMvc
        .perform(
            post("/o/{organizationId}/login/webauthn/finish", ORGANIZATION_ID)
                .session((MockHttpSession) startResult.getRequest().getSession())
                .contentType("application/json")
                .content("{\"credential\":\"irrelevant\"}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.redirectTo")
                .value("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc"));

    verify(recordLoginDevice).handle(any());
    verify(recordLoginEvent).handle(any());
  }
}

package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.deletewebauthncredential.DeleteWebAuthnCredentialCommand;
import com.clavaris.identity.application.usecase.deletewebauthncredential.DeleteWebAuthnCredentialUseCase;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount.ListWebAuthnCredentialsForAccountUseCase;
import com.clavaris.identity.application.usecase.registerwebauthncredential.CompleteWebAuthnRegistrationUseCase;
import com.clavaris.identity.application.usecase.registerwebauthncredential.InvalidWebAuthnRegistrationException;
import com.clavaris.identity.application.usecase.registerwebauthncredential.StartWebAuthnRegistrationUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialParameters;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserIdentity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

class AccountWebAuthnCredentialsControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private StartWebAuthnRegistrationUseCase startRegistration;
  private CompleteWebAuthnRegistrationUseCase completeRegistration;
  private ListWebAuthnCredentialsForAccountUseCase listCredentials;
  private DeleteWebAuthnCredentialUseCase deleteCredential;
  private GetAccountForOrganizationUseCase getAccount;
  private CurrentAccountResolver currentAccount;
  private RequireRecentAuthentication requireRecentAuthentication;
  private AccountId accountId;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    startRegistration = mock(StartWebAuthnRegistrationUseCase.class);
    completeRegistration = mock(CompleteWebAuthnRegistrationUseCase.class);
    listCredentials = mock(ListWebAuthnCredentialsForAccountUseCase.class);
    deleteCredential = mock(DeleteWebAuthnCredentialUseCase.class);
    getAccount = mock(GetAccountForOrganizationUseCase.class);
    currentAccount = mock(CurrentAccountResolver.class);
    requireRecentAuthentication = mock(RequireRecentAuthentication.class);

    accountId = AccountId.newId();
    Account account =
        Account.register(new OrganizationId(ORGANIZATION_ID), new Email("user@example.com"));
    when(currentAccount.resolve(any())).thenReturn(Optional.of(accountId));
    when(getAccount.handle(any())).thenReturn(Optional.of(account));
    when(listCredentials.handle(any())).thenReturn(List.of());
    // Default: every test below exercises a recently-authenticated session unless it
    // deliberately overrides this stub to prove the reverification-redirect path itself.
    when(requireRecentAuthentication.isStale(ORGANIZATION_ID)).thenReturn(false);

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
                new AccountWebAuthnCredentialsController(
                    startRegistration,
                    completeRegistration,
                    listCredentials,
                    deleteCredential,
                    getAccount,
                    currentAccount,
                    requireRecentAuthentication))
            .setViewResolvers(viewResolver)
            .build();
  }

  // The controller's own /registration/finish round-trips this through the REAL
  // PublicKeyCredentialCreationOptions.fromJson(...) static deserializer.
  private static String realCreationOptionsJson() throws Exception {
    PublicKeyCredentialCreationOptions options =
        PublicKeyCredentialCreationOptions.builder()
            .rp(RelyingPartyIdentity.builder().id("localhost").name("Clavaris").build())
            .user(
                UserIdentity.builder()
                    .name("user@example.com")
                    .displayName("user@example.com")
                    .id(new ByteArray(new byte[] {1}))
                    .build())
            .challenge(new ByteArray(new byte[] {2, 3, 4}))
            .pubKeyCredParams(List.of(PublicKeyCredentialParameters.ES256))
            .build();
    return options.toJson();
  }

  @Test
  void showRendersThePasskeysListPage() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/account/passkeys", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/passkeys"));
  }

  @Test
  void startRegistrationReturnsTheCredentialsCreateJsonAndStashesTheOptionsInSession()
      throws Exception {
    PublicKeyCredentialCreationOptions options = mock(PublicKeyCredentialCreationOptions.class);
    when(options.toJson()).thenReturn(realCreationOptionsJson());
    when(options.toCredentialsCreateJson()).thenReturn("{\"publicKey\":{}}");
    when(startRegistration.handle(any())).thenReturn(options);

    mockMvc
        .perform(post("/o/{organizationId}/account/passkeys/registration/start", ORGANIZATION_ID))
        .andExpect(status().isOk());
  }

  @Test
  void finishRegistrationWithoutAPendingChallengeReturnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/account/passkeys/registration/finish", ORGANIZATION_ID)
                .contentType("application/json")
                .content("{\"credential\":\"irrelevant\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  void finishRegistrationWithAFailedCeremonyReturnsBadRequest() throws Exception {
    PublicKeyCredentialCreationOptions options = mock(PublicKeyCredentialCreationOptions.class);
    when(options.toJson()).thenReturn(realCreationOptionsJson());
    when(options.toCredentialsCreateJson()).thenReturn("{\"publicKey\":{}}");
    when(startRegistration.handle(any())).thenReturn(options);
    doThrow(new InvalidWebAuthnRegistrationException("boom"))
        .when(completeRegistration)
        .handle(any());

    MvcResult startResult =
        mockMvc
            .perform(
                post("/o/{organizationId}/account/passkeys/registration/start", ORGANIZATION_ID))
            .andReturn();

    mockMvc
        .perform(
            post("/o/{organizationId}/account/passkeys/registration/finish", ORGANIZATION_ID)
                .session((MockHttpSession) startResult.getRequest().getSession())
                .contentType("application/json")
                .content("{\"credential\":\"irrelevant\",\"nickname\":\"My key\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  void finishRegistrationOnSuccessReturnsNoContent() throws Exception {
    PublicKeyCredentialCreationOptions options = mock(PublicKeyCredentialCreationOptions.class);
    when(options.toJson()).thenReturn(realCreationOptionsJson());
    when(options.toCredentialsCreateJson()).thenReturn("{\"publicKey\":{}}");
    when(startRegistration.handle(any())).thenReturn(options);

    MvcResult startResult =
        mockMvc
            .perform(
                post("/o/{organizationId}/account/passkeys/registration/start", ORGANIZATION_ID))
            .andReturn();

    mockMvc
        .perform(
            post("/o/{organizationId}/account/passkeys/registration/finish", ORGANIZATION_ID)
                .session((MockHttpSession) startResult.getRequest().getSession())
                .contentType("application/json")
                .content("{\"credential\":\"irrelevant\",\"nickname\":\"My key\"}"))
        .andExpect(status().isNoContent());

    verify(completeRegistration).handle(any());
  }

  @Test
  void deleteIsOwnershipScopedToTheCurrentAccountAndRedirectsBackToTheList() throws Exception {
    UUID credentialId = UUID.randomUUID();

    mockMvc
        .perform(
            post(
                "/o/{organizationId}/account/passkeys/{credentialId}/delete",
                ORGANIZATION_ID,
                credentialId))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/account/passkeys"));

    verify(deleteCredential).handle(new DeleteWebAuthnCredentialCommand(credentialId, accountId));
  }

  // Clerk "Sessions" settings parity.
  @Test
  void deleteRedirectsToLoginWithoutDeletingWhenTheAuthenticationIsStale() throws Exception {
    when(requireRecentAuthentication.isStale(ORGANIZATION_ID)).thenReturn(true);
    UUID credentialId = UUID.randomUUID();

    mockMvc
        .perform(
            post(
                "/o/{organizationId}/account/passkeys/{credentialId}/delete",
                ORGANIZATION_ID,
                credentialId))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login"));

    verify(deleteCredential, never()).handle(any());
  }
}

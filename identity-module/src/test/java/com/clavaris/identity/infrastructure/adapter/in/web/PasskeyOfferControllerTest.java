package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount.ListWebAuthnCredentialsForAccountUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.WebAuthnCredential;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PasskeyOfferControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final String OFFER = "/o/{organizationId}/account/passkey-offer";
  private static final String NEXT = "https://app.example.com/dashboard";

  private ListWebAuthnCredentialsForAccountUseCase listCredentials;
  private CurrentAccountResolver currentAccount;
  private MockMvc mockMvc;
  private MockHttpSession session;
  private AccountAuthenticationPolicySnapshot policy =
      AccountAuthenticationPolicySnapshot.defaults();

  @BeforeEach
  void setUp() {
    listCredentials = mock(ListWebAuthnCredentialsForAccountUseCase.class);
    currentAccount = mock(CurrentAccountResolver.class);
    when(listCredentials.handle(any())).thenReturn(List.of());
    when(currentAccount.resolve(any())).thenReturn(Optional.of(AccountId.newId()));
    session = new MockHttpSession();
    session.setAttribute(PasskeyOfferPendingState.NEXT_ATTRIBUTE, NEXT);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PasskeyOfferController(
                    new PasskeyOffer(listCredentials, organizationId -> policy), currentAccount))
            .build();
  }

  @Test
  void theOfferIsShownToAnAccountWithoutAPasskey() throws Exception {
    mockMvc
        .perform(get(OFFER, ORGANIZATION_ID).session(session))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/passkey-offer"));
  }

  @Test
  void anOrganizationThatSwitchedTheOfferOffSincePageWasLinkedCarriesStraightOn() throws Exception {
    policy =
        new AccountAuthenticationPolicySnapshot(
            false,
            com.clavaris.identity.application.usecase.requestemailverification
                .EmailVerificationMethod.LINK,
            false,
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            false,
            true,
            false);

    mockMvc
        .perform(get(OFFER, ORGANIZATION_ID).session(session))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(NEXT));
  }

  @Test
  void onceAPasskeyExistsTheOfferCarriesOnToWhereThePersonWasGoing() throws Exception {
    when(listCredentials.handle(any())).thenReturn(List.of(mock(WebAuthnCredential.class)));

    mockMvc
        .perform(get(OFFER, ORGANIZATION_ID).session(session))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(NEXT));
  }

  @Test
  void notNowGoesOnWithoutRemembering() throws Exception {
    final MvcResult result =
        mockMvc
            .perform(post(OFFER + "/skip", ORGANIZATION_ID).session(session))
            .andExpect(redirectedUrl(NEXT))
            .andReturn();

    assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    assertThat(session.getAttribute(PasskeyOfferPendingState.NEXT_ATTRIBUTE)).isNull();
  }

  @Test
  void dontAskAgainGoesOnAndRemembersForThisBrowser() throws Exception {
    final MvcResult result =
        mockMvc
            .perform(post(OFFER + "/dismiss", ORGANIZATION_ID).session(session))
            .andExpect(redirectedUrl(NEXT))
            .andReturn();

    assertThat(result.getResponse().getHeader("Set-Cookie"))
        .startsWith("clavaris_passkey_offer_" + ORGANIZATION_ID + "=1")
        .contains("HttpOnly")
        .contains("SameSite=Lax");
  }

  @Test
  void theDestinationIsNeverTakenFromTheRequest() throws Exception {
    mockMvc
        .perform(
            post(OFFER + "/skip", ORGANIZATION_ID)
                .session(session)
                .param("next", "https://evil.example")
                .param("redirectUrl", "https://evil.example"))
        .andExpect(redirectedUrl(NEXT));
  }

  @Test
  void aSecondVisitCannotReplayTheParkedDestination() throws Exception {
    mockMvc.perform(post(OFFER + "/skip", ORGANIZATION_ID).session(session));

    mockMvc
        .perform(post(OFFER + "/skip", ORGANIZATION_ID).session(session))
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/account/passkeys"));
  }

  @Test
  void visitingWithNothingParkedLandsOnThePasskeysPage() throws Exception {
    mockMvc
        .perform(post(OFFER + "/skip", ORGANIZATION_ID))
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/account/passkeys"));
  }
}

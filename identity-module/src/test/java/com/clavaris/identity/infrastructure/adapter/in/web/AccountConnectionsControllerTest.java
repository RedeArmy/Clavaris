package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.disconnectsocialidentity.DisconnectSocialIdentityUseCase;
import com.clavaris.identity.application.usecase.disconnectsocialidentity.LastSignInMethodException;
import com.clavaris.identity.application.usecase.disconnectsocialidentity.SocialIdentityNotLinkedException;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class AccountConnectionsControllerTest {

  private static final UUID ORG = UUID.randomUUID();
  private static final String URL = "/o/" + ORG + "/account/connections";

  private final AccountId accountId = AccountId.newId();

  private SocialIdentityRepository identities;
  private OrganizationSocialLoginPolicyProvider policy;
  private DisconnectSocialIdentityUseCase disconnect;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    identities = mock(SocialIdentityRepository.class);
    policy = mock(OrganizationSocialLoginPolicyProvider.class);
    disconnect = mock(DisconnectSocialIdentityUseCase.class);
    CurrentAccountResolver currentAccount = mock(CurrentAccountResolver.class);
    when(currentAccount.resolve(any())).thenReturn(Optional.of(accountId));
    when(identities.findAllByAccountId(accountId)).thenReturn(List.of());
    mockMvc =
        SelfServiceMvc.standalone(
            new AccountConnectionsController(currentAccount, identities, policy, disconnect));
  }

  @Test
  void listsLinkedProvidersAndTheOnesStillConnectable() throws Exception {
    OrganizationId org = new OrganizationId(ORG);
    when(identities.findAllByAccountId(accountId))
        .thenReturn(List.of(SocialIdentity.link(accountId, org, SocialProvider.GITHUB, "gh-1")));
    when(policy.allowedProviders(org))
        .thenReturn(Set.of(SocialProvider.GITHUB, SocialProvider.GOOGLE));

    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/connections"))
        .andExpect(model().attribute("linked", Set.of(SocialProvider.GITHUB)))
        .andExpect(model().attribute("connectable", List.of(SocialProvider.GOOGLE)));
  }

  @Test
  void connectingPlantsTheIntentAndHandsOffToTheProvider() throws Exception {
    when(policy.isProviderAllowed(new OrganizationId(ORG), SocialProvider.GITHUB)).thenReturn(true);
    MockHttpSession session = new MockHttpSession();

    MvcResult result =
        mockMvc
            .perform(post(URL + "/github/connect").session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

    assertThat(result.getResponse().getRedirectedUrl())
        .isEqualTo(
            "/oauth2/authorization/github?clavaris_tenant=" + ORG.toString().replace("-", ""));
    assertThat(
            session.getAttribute(SocialLoginRedirectController.LINK_ACCOUNT_ID_SESSION_ATTRIBUTE))
        .isEqualTo(accountId.value().toString());
    assertThat(
            session.getAttribute(SocialLoginRedirectController.LINK_REQUESTED_AT_SESSION_ATTRIBUTE))
        .isNotNull();
    assertThat(
            session.getAttribute(SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE))
        .isEqualTo(ORG.toString());
    assertThat(session.getAttribute(SocialLoginRedirectController.LINK_FACTORS_SESSION_ATTRIBUTE))
        .as("the factors of the session that asked, kept for the restore (TD-SEC-072)")
        .isInstanceOf(java.util.List.class);
  }

  @Test
  void connectingAProviderTheOrganizationHasNotEnabledPlantsNothing() throws Exception {
    MockHttpSession session = new MockHttpSession();

    mockMvc
        .perform(post(URL + "/github/connect").session(session))
        .andExpect(redirectedUrl(URL + "?connectError"));

    assertThat(
            session.getAttribute(SocialLoginRedirectController.LINK_ACCOUNT_ID_SESSION_ATTRIBUTE))
        .isNull();
  }

  @Test
  void connectingAnUnknownProviderIsAnError() throws Exception {
    mockMvc.perform(post(URL + "/myspace/connect")).andExpect(redirectedUrl(URL + "?connectError"));
  }

  @Test
  void disconnectingRedirectsWithSuccess() throws Exception {
    mockMvc
        .perform(post(URL + "/github/disconnect"))
        .andExpect(redirectedUrl(URL + "?disconnected"));

    verify(disconnect).handle(any());
  }

  @Test
  void disconnectingTheLastSignInMethodIsRefused() throws Exception {
    doThrow(new LastSignInMethodException()).when(disconnect).handle(any());

    mockMvc.perform(post(URL + "/github/disconnect")).andExpect(redirectedUrl(URL + "?lastMethod"));
  }

  @Test
  void disconnectingAProviderThatIsNotLinkedOrUnknownIsAnError() throws Exception {
    doThrow(new SocialIdentityNotLinkedException()).when(disconnect).handle(any());

    mockMvc
        .perform(post(URL + "/github/disconnect"))
        .andExpect(redirectedUrl(URL + "?connectError"));
    mockMvc
        .perform(post(URL + "/myspace/disconnect"))
        .andExpect(redirectedUrl(URL + "?connectError"));
  }

  // The sessions are allow-listed JSON (TD-ARCH-039): a type of ours is written without a type id
  // and cannot be read back, so a connect that stored an Instant looked like a plain sign-in when
  // the provider called back. Only Strings, and lists of Strings, survive.
  @Test
  void everythingTheConnectKeepsInTheSessionSurvivesJsonSessions() throws Exception {
    when(policy.isProviderAllowed(new OrganizationId(ORG), SocialProvider.GITHUB)).thenReturn(true);
    MockHttpSession session = new MockHttpSession();

    mockMvc.perform(post(URL + "/github/connect").session(session));

    for (String name : java.util.Collections.list(session.getAttributeNames())) {
      Object value = session.getAttribute(name);
      assertThat(value)
          .as("session attribute " + name)
          .satisfiesAnyOf(
              v -> assertThat(v).isInstanceOf(String.class),
              v ->
                  assertThat(v)
                      .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                      .allMatch(String.class::isInstance));
    }
  }
}

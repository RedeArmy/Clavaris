package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.revokealloauthgrantsforaccount.RevokeAllOAuthGrantsForAccountCommand;
import com.clavaris.identity.application.usecase.revokealloauthgrantsforaccount.RevokeAllOAuthGrantsForAccountUseCase;
import com.clavaris.identity.application.usecase.revokeoauthgrant.OAuthGrantNotFoundException;
import com.clavaris.identity.application.usecase.revokeoauthgrant.RevokeOAuthGrantCommand;
import com.clavaris.identity.application.usecase.revokeoauthgrant.RevokeOAuthGrantUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Same standalone MockMvc setup as {@link PlatformAccountLifecycleControllerTest}. */
class PlatformAccountOAuthGrantsAdminControllerTest {

  private static final PlatformAccountId OWNER_ID = PlatformAccountId.newId();
  private static final AuditActor ACTOR = AuditActor.platformAccount(OWNER_ID.value());

  private GetAccountForOrganizationUseCase getAccount;
  private RevokeOAuthGrantUseCase revokeGrant;
  private RevokeAllOAuthGrantsForAccountUseCase revokeAllGrants;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;
  private Account account;

  @BeforeEach
  void setUp() {
    getAccount = mock(GetAccountForOrganizationUseCase.class);
    revokeGrant = mock(RevokeOAuthGrantUseCase.class);
    revokeAllGrants = mock(RevokeAllOAuthGrantsForAccountUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();
    account = Account.register(new OrganizationId(organizationId), new Email("ada@example.com"));

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(getAccount.handle(any())).thenReturn(Optional.of(account));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformAccountOAuthGrantsAdminController(
                    getAccount,
                    revokeGrant,
                    revokeAllGrants,
                    organizationResolver,
                    currentPlatformAccount))
            .build();
  }

  private String profileUrl() {
    return "/platform/dashboard/organizations/" + organizationId + "/users/" + account.id().value();
  }

  @Test
  void revokeDelegatesAndRedirects() throws Exception {
    mockMvc
        .perform(post(profileUrl() + "/oauth-grants/auth-1/revoke"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(profileUrl()));

    verify(revokeGrant).handle(new RevokeOAuthGrantCommand(account.id(), "auth-1", ACTOR));
  }

  @Test
  void revokeIsBenignWhenTheGrantIsAlreadyGone() throws Exception {
    doThrow(new OAuthGrantNotFoundException("auth-1")).when(revokeGrant).handle(any());

    mockMvc
        .perform(post(profileUrl() + "/oauth-grants/auth-1/revoke"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(profileUrl()));
  }

  @Test
  void revokeAllDelegatesAndRedirects() throws Exception {
    mockMvc
        .perform(post(profileUrl() + "/oauth-grants/revoke-all"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(profileUrl()));

    verify(revokeAllGrants).handle(new RevokeAllOAuthGrantsForAccountCommand(account.id(), ACTOR));
  }

  @Test
  void returnsNotFoundWhenTheAccountIsUnknownOrBelongsToAnotherOrganization() throws Exception {
    when(getAccount.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(post(profileUrl() + "/oauth-grants/revoke-all"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsUnknownOrNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc
        .perform(post(profileUrl() + "/oauth-grants/revoke-all"))
        .andExpect(status().isNotFound());
  }
}

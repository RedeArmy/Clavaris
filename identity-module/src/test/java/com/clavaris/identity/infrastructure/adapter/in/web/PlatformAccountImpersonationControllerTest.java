package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.impersonateaccount.AccountNotActiveException;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonateAccountResult;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonateAccountUseCase;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonationClientNotFoundException;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonationScopeNotAllowedException;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonationTokenMinter;
import com.clavaris.identity.application.usecase.impersonateaccount.MintedImpersonationToken;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlatformAccountImpersonationControllerTest {

  private static final PlatformAccountId OWNER_ID = PlatformAccountId.newId();

  private GetAccountForOrganizationUseCase getAccount;
  private ImpersonateAccountUseCase impersonateAccount;
  private ImpersonationTokenMinter tokenMinter;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;
  private Account account;

  @BeforeEach
  void setUp() {
    getAccount = mock(GetAccountForOrganizationUseCase.class);
    impersonateAccount = mock(ImpersonateAccountUseCase.class);
    tokenMinter = mock(ImpersonationTokenMinter.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();
    account = Account.register(new OrganizationId(organizationId), new Email("ada@example.com"));

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(getAccount.handle(any())).thenReturn(Optional.of(account));
    when(impersonateAccount.handle(any()))
        .thenReturn(new ImpersonateAccountResult(account.id(), new OrganizationId(organizationId)));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformAccountImpersonationController(
                    getAccount,
                    impersonateAccount,
                    tokenMinter,
                    organizationResolver,
                    currentPlatformAccount))
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/"
        + organizationId
        + "/users/"
        + account.id().value()
        + "/impersonate";
  }

  private String redirectTarget() {
    return "/platform/dashboard/organizations/" + organizationId + "/users/" + account.id().value();
  }

  @Test
  void mintsATokenAndCarriesItAsAFlashAttributeOnSuccess() throws Exception {
    MintedImpersonationToken token =
        new MintedImpersonationToken(
            "raw-access-token", "Bearer", Instant.now().plusSeconds(300), Set.of("openid"));
    when(tokenMinter.mint(any(), any(), any(), any(), any(), any())).thenReturn(token);

    mockMvc
        .perform(post(path()).param("clientId", "jobseeker-web").param("scopes", "openid"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(redirectTarget()))
        .andExpect(flash().attribute("impersonationToken", token));
  }

  @Test
  void carriesAnErrorFlashAttributeWhenTheAccountIsNotActive() throws Exception {
    when(impersonateAccount.handle(any())).thenThrow(new AccountNotActiveException(account.id()));

    mockMvc
        .perform(post(path()).param("clientId", "jobseeker-web"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(redirectTarget()))
        .andExpect(flash().attributeExists("impersonationError"));
  }

  @Test
  void carriesAnErrorFlashAttributeWhenTheClientIsNotFound() throws Exception {
    when(tokenMinter.mint(any(), any(), any(), any(), any(), any()))
        .thenThrow(new ImpersonationClientNotFoundException("unknown-client"));

    mockMvc
        .perform(post(path()).param("clientId", "unknown-client"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attributeExists("impersonationError"));
  }

  @Test
  void carriesAnErrorFlashAttributeWhenTheScopeIsNotAllowed() throws Exception {
    when(tokenMinter.mint(any(), any(), any(), any(), any(), any()))
        .thenThrow(new ImpersonationScopeNotAllowedException("jobseeker-web"));

    mockMvc
        .perform(post(path()).param("clientId", "jobseeker-web").param("scopes", "admin"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attributeExists("impersonationError"));
  }

  @Test
  void returnsNotFoundWhenTheAccountIsUnknownOrBelongsToAnotherOrganization() throws Exception {
    when(getAccount.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(post(path()).param("clientId", "jobseeker-web"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsUnknownOrNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc
        .perform(post(path()).param("clientId", "jobseeker-web"))
        .andExpect(status().isNotFound());
  }
}

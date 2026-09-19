package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.identity.application.usecase.banaccount.BanAccountCommand;
import com.clavaris.identity.application.usecase.banaccount.BanAccountUseCase;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountCommand;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountUseCase;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.reactivateaccount.ReactivateAccountCommand;
import com.clavaris.identity.application.usecase.reactivateaccount.ReactivateAccountUseCase;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountCommand;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountUseCase;
import com.clavaris.identity.application.usecase.unbanaccount.UnbanAccountCommand;
import com.clavaris.identity.application.usecase.unbanaccount.UnbanAccountUseCase;
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

/** Same standalone MockMvc setup as {@link PlatformAccountImpersonationControllerTest}. */
class PlatformAccountLifecycleControllerTest {

  private static final PlatformAccountId OWNER_ID = PlatformAccountId.newId();

  private GetAccountForOrganizationUseCase getAccount;
  private SuspendAccountUseCase suspendAccount;
  private ReactivateAccountUseCase reactivateAccount;
  private BanAccountUseCase banAccount;
  private UnbanAccountUseCase unbanAccount;
  private DeleteAccountUseCase deleteAccount;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;
  private Account account;

  @BeforeEach
  void setUp() {
    getAccount = mock(GetAccountForOrganizationUseCase.class);
    suspendAccount = mock(SuspendAccountUseCase.class);
    reactivateAccount = mock(ReactivateAccountUseCase.class);
    banAccount = mock(BanAccountUseCase.class);
    unbanAccount = mock(UnbanAccountUseCase.class);
    deleteAccount = mock(DeleteAccountUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();
    account = Account.register(new OrganizationId(organizationId), new Email("ada@example.com"));

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(getAccount.handle(any())).thenReturn(Optional.of(account));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformAccountLifecycleController(
                    getAccount,
                    suspendAccount,
                    reactivateAccount,
                    banAccount,
                    unbanAccount,
                    deleteAccount,
                    organizationResolver,
                    currentPlatformAccount))
            .build();
  }

  private String path(String action) {
    return "/platform/dashboard/organizations/"
        + organizationId
        + "/users/"
        + account.id().value()
        + "/"
        + action;
  }

  private String usersListRedirect() {
    return "/platform/dashboard/organizations/" + organizationId + "/users";
  }

  @Test
  void suspendCallsTheUseCaseAndRedirectsToTheUsersList() throws Exception {
    mockMvc
        .perform(post(path("suspend")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(usersListRedirect()));

    verify(suspendAccount).handle(new SuspendAccountCommand(account.id(), platformAccountActor()));
  }

  @Test
  void reactivateCallsTheUseCaseAndRedirectsToTheUsersList() throws Exception {
    mockMvc
        .perform(post(path("reactivate")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(usersListRedirect()));

    verify(reactivateAccount)
        .handle(new ReactivateAccountCommand(account.id(), platformAccountActor()));
  }

  @Test
  void banCallsTheUseCaseAndRedirectsToTheUsersList() throws Exception {
    mockMvc
        .perform(post(path("ban")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(usersListRedirect()));

    verify(banAccount).handle(new BanAccountCommand(account.id(), platformAccountActor()));
  }

  @Test
  void unbanCallsTheUseCaseAndRedirectsToTheUsersList() throws Exception {
    mockMvc
        .perform(post(path("unban")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(usersListRedirect()));

    verify(unbanAccount).handle(new UnbanAccountCommand(account.id(), platformAccountActor()));
  }

  @Test
  void deleteCallsTheUseCaseAndRedirectsToTheUsersList() throws Exception {
    mockMvc
        .perform(post(path("delete")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(usersListRedirect()));

    verify(deleteAccount).handle(new DeleteAccountCommand(account.id(), platformAccountActor()));
  }

  @Test
  void returnsNotFoundWhenTheAccountIsUnknownOrBelongsToAnotherOrganization() throws Exception {
    when(getAccount.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(post(path("suspend"))).andExpect(status().isNotFound());
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsUnknownOrNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(post(path("delete"))).andExpect(status().isNotFound());
  }

  private static com.clavaris.common.domain.model.AuditActor platformAccountActor() {
    return com.clavaris.common.domain.model.AuditActor.platformAccount(OWNER_ID.value());
  }
}

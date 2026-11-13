package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.resettotpforaccount.ResetTotpForAccountCommand;
import com.clavaris.identity.application.usecase.resettotpforaccount.ResetTotpForAccountUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlatformAccountTotpResetControllerTest {

  private static final PlatformAccountId OWNER_ID = PlatformAccountId.newId();

  private GetAccountForOrganizationUseCase getAccount;
  private ResetTotpForAccountUseCase resetTotp;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private UUID organizationId;
  private Account account;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    getAccount = mock(GetAccountForOrganizationUseCase.class);
    resetTotp = mock(ResetTotpForAccountUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    final CurrentPlatformAccountResolver currentPlatformAccount =
        mock(CurrentPlatformAccountResolver.class);
    organizationId = UUID.randomUUID();
    account =
        Account.register(
            new OrganizationId(organizationId), new Email("lost@example.com"), Instant.now());
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(getAccount.handle(any())).thenReturn(Optional.of(account));
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformAccountTotpResetController(
                    getAccount, resetTotp, organizationResolver, currentPlatformAccount))
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/"
        + organizationId
        + "/users/"
        + account.id().value()
        + "/totp/reset";
  }

  @Test
  void theResetRunsAsTheOperatorAndReturnsToTheAccount() throws Exception {
    mockMvc
        .perform(post(path()))
        .andExpect(
            redirectedUrl(
                "/platform/dashboard/organizations/"
                    + organizationId
                    + "/users/"
                    + account.id().value()));

    verify(resetTotp)
        .handle(
            new ResetTotpForAccountCommand(
                account.id(), AuditActor.platformAccount(OWNER_ID.value())));
  }

  @Test
  void anAccountThatIsNotInTheOperatorsOrganizationIsNotFoundAndNothingIsReset() throws Exception {
    when(getAccount.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(post(path())).andExpect(status().isNotFound());

    verify(resetTotp, never()).handle(any());
  }

  @Test
  void anOrganizationThatIsNotTheCallersIsNotFound() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(post(path())).andExpect(status().isNotFound());

    verify(resetTotp, never()).handle(any());
  }

  @Test
  void theResetIsAPostOnly() throws Exception {
    mockMvc.perform(get(path())).andExpect(status().isMethodNotAllowed());
  }
}

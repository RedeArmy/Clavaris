package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.approveaccountregistration.ApproveAccountRegistrationCommand;
import com.clavaris.identity.application.usecase.approveaccountregistration.ApproveAccountRegistrationUseCase;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.listpendingaccountsfororganization.ListPendingAccountsForOrganizationQuery;
import com.clavaris.identity.application.usecase.listpendingaccountsfororganization.ListPendingAccountsForOrganizationUseCase;
import com.clavaris.identity.application.usecase.listpendingaccountsfororganization.ListPendingAccountsForOrganizationUseCase.PendingAccounts;
import com.clavaris.identity.application.usecase.rejectaccountregistration.RejectAccountRegistrationCommand;
import com.clavaris.identity.application.usecase.rejectaccountregistration.RejectAccountRegistrationUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.List;
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

class PlatformPendingAccountsControllerTest {

  private static final PlatformAccountId OWNER_ID = PlatformAccountId.newId();

  private ListPendingAccountsForOrganizationUseCase listPending;
  private ApproveAccountRegistrationUseCase approve;
  private RejectAccountRegistrationUseCase reject;
  private GetAccountForOrganizationUseCase getAccount;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private UUID organizationId;
  private Account waiting;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    listPending = mock(ListPendingAccountsForOrganizationUseCase.class);
    approve = mock(ApproveAccountRegistrationUseCase.class);
    reject = mock(RejectAccountRegistrationUseCase.class);
    getAccount = mock(GetAccountForOrganizationUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    final CurrentPlatformAccountResolver currentPlatformAccount =
        mock(CurrentPlatformAccountResolver.class);
    organizationId = UUID.randomUUID();
    waiting =
        Account.registerPendingApproval(
            new OrganizationId(organizationId), new Email("waiting@example.com"));

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(getAccount.handle(any())).thenReturn(Optional.of(waiting));
    when(listPending.handle(any())).thenReturn(new PendingAccounts(List.of(waiting), 1));

    final GenericApplicationContext applicationContext = new GenericApplicationContext();
    applicationContext.refresh();
    final SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
    templateResolver.setApplicationContext(applicationContext);
    templateResolver.setPrefix("classpath:/templates/");
    templateResolver.setSuffix(".html");
    final SpringTemplateEngine templateEngine = new SpringTemplateEngine();
    templateEngine.setTemplateResolver(templateResolver);
    final ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
    viewResolver.setTemplateEngine(templateEngine);

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformPendingAccountsController(
                    listPending,
                    approve,
                    reject,
                    getAccount,
                    organizationResolver,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String base() {
    return "/platform/dashboard/organizations/" + organizationId + "/users/pending";
  }

  @Test
  void theQueueListsWhoIsWaitingWithAnApproveAndARejectEach() throws Exception {
    final String body =
        mockMvc
            .perform(get(base()))
            .andExpect(status().isOk())
            .andExpect(view().name("identity/platform/organization-users-pending"))
            .andExpect(model().attribute("pendingTotal", 1L))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .contains("waiting@example.com")
        .contains("/pending/" + waiting.id().value() + "/approve")
        .contains("/pending/" + waiting.id().value() + "/reject")
        .contains("Pending approval");
    verify(listPending)
        .handle(
            argThat(
                (ListPendingAccountsForOrganizationQuery query) ->
                    query.organizationId().equals(new OrganizationId(organizationId))
                        && query.limit() == PlatformPendingAccountsController.PAGE_SIZE));
  }

  @Test
  void anEmptyQueueSaysSoAndOffersNothingToDecide() throws Exception {
    when(listPending.handle(any())).thenReturn(new PendingAccounts(List.of(), 0));

    final String body = mockMvc.perform(get(base())).andReturn().getResponse().getContentAsString();

    assertThat(body).contains("Nobody is waiting for approval.").doesNotContain("/approve");
  }

  @Test
  void aQueueLongerThanThePageSaysThereIsMore() throws Exception {
    when(listPending.handle(any())).thenReturn(new PendingAccounts(List.of(waiting), 120));

    final String body = mockMvc.perform(get(base())).andReturn().getResponse().getContentAsString();

    assertThat(body).contains("out of 120");
  }

  @Test
  void anHtmxRequestGetsTheFragmentOnly() throws Exception {
    mockMvc
        .perform(get(base()).header("HX-Request", "true"))
        .andExpect(view().name("identity/platform/organization-users-pending :: pending"));
  }

  @Test
  void approvingDecidesItAsTheOperatorAndGoesBackToTheQueue() throws Exception {
    mockMvc
        .perform(post(base() + "/" + waiting.id().value() + "/approve"))
        .andExpect(redirectedUrl(base()));

    verify(approve)
        .handle(
            new ApproveAccountRegistrationCommand(
                waiting.id(), AuditActor.platformAccount(OWNER_ID.value())));
  }

  @Test
  void rejectingKeepsTheReasonTrimmedAndOptional() throws Exception {
    mockMvc
        .perform(post(base() + "/" + waiting.id().value() + "/reject").param("reason", "  spam  "))
        .andExpect(redirectedUrl(base()));
    mockMvc
        .perform(post(base() + "/" + waiting.id().value() + "/reject").param("reason", "   "))
        .andExpect(redirectedUrl(base()));
    mockMvc
        .perform(post(base() + "/" + waiting.id().value() + "/reject"))
        .andExpect(redirectedUrl(base()));

    verify(reject)
        .handle(
            new RejectAccountRegistrationCommand(
                waiting.id(), "spam", AuditActor.platformAccount(OWNER_ID.value())));
    verify(reject, times(2))
        .handle(
            new RejectAccountRegistrationCommand(
                waiting.id(), null, AuditActor.platformAccount(OWNER_ID.value())));
  }

  @Test
  void aVeryLongReasonIsCutRatherThanTheDecisionRefused() throws Exception {
    mockMvc
        .perform(
            post(base() + "/" + waiting.id().value() + "/reject").param("reason", "x".repeat(2000)))
        .andExpect(redirectedUrl(base()));

    verify(reject)
        .handle(
            argThat(
                (RejectAccountRegistrationCommand command) ->
                    command.reason().length()
                        == PlatformPendingAccountsController.MAX_REASON_LENGTH));
  }

  @Test
  void anAccountThatIsNotInThisOrganizationIsNotFoundAndNothingIsDecided() throws Exception {
    when(getAccount.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(post(base() + "/" + UUID.randomUUID() + "/approve"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post(base() + "/" + UUID.randomUUID() + "/reject"))
        .andExpect(status().isNotFound());

    verify(approve, never()).handle(any());
    verify(reject, never()).handle(any());
  }

  @Test
  void anOrganizationThatIsNotTheCallersIsNotFound() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(base())).andExpect(status().isNotFound());
    mockMvc
        .perform(post(base() + "/" + waiting.id().value() + "/approve"))
        .andExpect(status().isNotFound());

    verify(approve, never()).handle(any());
  }
}

package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.getauditlogforaccount.GetAuditLogForAccountUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.Username;
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

/**
 * Same standalone MockMvc + real Thymeleaf setup as {@link PlatformAccountDetailControllerTest}.
 */
class PlatformAccountAuditLogControllerTest {

  private static final PlatformAccountId OWNER_ID = PlatformAccountId.newId();

  private GetAccountForOrganizationUseCase getAccount;
  private GetAuditLogForAccountUseCase getAuditLog;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;
  private Account account;

  @BeforeEach
  void setUp() {
    getAccount = mock(GetAccountForOrganizationUseCase.class);
    getAuditLog = mock(GetAuditLogForAccountUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();
    account = Account.register(new OrganizationId(organizationId), new Email("ada@example.com"));

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(getAccount.handle(any())).thenReturn(Optional.of(account));
    when(getAuditLog.handle(any())).thenReturn(List.of());

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
                new PlatformAccountAuditLogController(
                    getAccount, getAuditLog, organizationResolver, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/"
        + organizationId
        + "/users/"
        + account.id().value()
        + "/audit-log";
  }

  @Test
  void showsTheAccountsAuditLog() throws Exception {
    AuditEvent event =
        AuditEvent.of(
            AuditActor.platformAccount(OWNER_ID.value()),
            "account.suspended",
            "Account",
            account.id().value().toString(),
            null);
    when(getAuditLog.handle(account.id().value())).thenReturn(List.of(event));

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/account-audit-log"))
        .andExpect(model().attribute("auditEvents", List.of(event)));
  }

  // Live-found bug, 2026-09-20: this breadcrumb's own "back to Account" link built its URL from
  // ${account.id()} instead of ${account.id().value()} — same AccountId-record-toString bug fixed
  // across the rest of this flow (organization-users.html, account-profile.html), just missed in
  // that pass since this page wasn't in the original bug report.
  @Test
  void breadcrumbLinksToTheRawAccountIdNotItsRecordToString() throws Exception {
    String rawId = account.id().value().toString();
    String profilePath = "/platform/dashboard/organizations/" + organizationId + "/users/" + rawId;

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("AccountId[value="))))
        .andExpect(content().string(containsString(profilePath)));
  }

  // organization-users.html's own row already prefers username over email once one is set (see
  // that file's own username-or-'—' column) — this page's breadcrumb link and descriptive
  // paragraph previously always showed the raw email regardless, even for an Account with a
  // friendlier username set.
  @Test
  void breadcrumbAndDescriptionPreferTheUsernameOverEmailWhenOneIsSet() throws Exception {
    account.assignUsername(new Username("ada-lovelace"));

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ada-lovelace")))
        .andExpect(content().string(not(containsString("ada@example.com"))));
  }

  @Test
  void breadcrumbFallsBackToEmailWhenNoUsernameIsSet() throws Exception {
    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ada@example.com")));
  }

  @Test
  void showsABackLinkToTheAccountsProfilePage() throws Exception {
    String rawId = account.id().value().toString();
    String profilePath = "/platform/dashboard/organizations/" + organizationId + "/users/" + rawId;

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("clavaris-back-link")))
        .andExpect(content().string(containsString("href=\"" + profilePath + "\"")));
  }

  @Test
  void returnsNotFoundWhenTheAccountIsUnknownOrBelongsToAnotherOrganization() throws Exception {
    when(getAccount.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsUnknownOrNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
  }
}

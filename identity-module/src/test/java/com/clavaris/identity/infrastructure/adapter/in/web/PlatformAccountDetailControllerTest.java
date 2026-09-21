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

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.impersonateaccount.OAuthClientsForOrganizationProvider;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ListActiveSessionsForAccountUseCase;
import com.clavaris.identity.application.usecase.listoauthgrantsforaccount.ListOAuthGrantsForAccountUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
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

/** Same standalone MockMvc + real Thymeleaf setup as {@link PlatformAccountsControllerTest}. */
class PlatformAccountDetailControllerTest {

  private static final PlatformAccountId OWNER_ID = PlatformAccountId.newId();

  private GetAccountForOrganizationUseCase getAccount;
  private KnownDeviceRepository knownDevices;
  private SocialIdentityRepository socialIdentities;
  private OAuthClientsForOrganizationProvider oauthClientsProvider;
  private ListActiveSessionsForAccountUseCase listSessions;
  private ListOAuthGrantsForAccountUseCase listOAuthGrants;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;
  private Account account;

  @BeforeEach
  void setUp() {
    getAccount = mock(GetAccountForOrganizationUseCase.class);
    knownDevices = mock(KnownDeviceRepository.class);
    socialIdentities = mock(SocialIdentityRepository.class);
    oauthClientsProvider = mock(OAuthClientsForOrganizationProvider.class);
    listSessions = mock(ListActiveSessionsForAccountUseCase.class);
    listOAuthGrants = mock(ListOAuthGrantsForAccountUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();
    account =
        Account.register(
            new OrganizationId(organizationId),
            new Email("ada@example.com"),
            "Ada",
            "Lovelace",
            null);

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(getAccount.handle(any())).thenReturn(Optional.of(account));
    when(knownDevices.findAllByAccountId(any())).thenReturn(List.of());
    when(socialIdentities.findAllByAccountId(any())).thenReturn(List.of());
    when(oauthClientsProvider.forOrganization(any())).thenReturn(List.of());
    when(listSessions.handle(any())).thenReturn(List.of());
    when(listOAuthGrants.handle(any())).thenReturn(List.of());

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
                new PlatformAccountDetailController(
                    getAccount,
                    knownDevices,
                    socialIdentities,
                    oauthClientsProvider,
                    listSessions,
                    listOAuthGrants,
                    organizationResolver,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/" + organizationId + "/users/" + account.id().value();
  }

  @Test
  void showsTheAccountsProfile() throws Exception {
    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/account-profile"))
        .andExpect(model().attribute("account", account));
  }

  @Test
  void returnsNotFoundWhenTheAccountIsUnknownOrBelongsToAnotherOrganization() throws Exception {
    when(getAccount.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
  }

  // organization-users.html's own row-menu "Impersonate user" link (?openImpersonate=true) must
  // resolve to this page auto-opening its own impersonate dialog — see that file's own comment
  // for the bug this replaced (a dead-end link to this same page with no way to reach the dialog).
  // The template's own explanatory comment above the button also contains the literal string
  // "data-dialog-open-on-load" (plain HTML comments render as-is, same codebase-wide convention
  // as this template's own SDE-III review comments) — asserting the real attribute-with-value
  // form so that comment can't produce a false positive here.
  private static final String IMPERSONATE_AUTO_OPEN_ATTRIBUTE = "data-dialog-open-on-load=\"true\"";

  @Test
  void openImpersonateQueryParamAutoOpensTheImpersonateDialog() throws Exception {
    mockMvc
        .perform(get(path()).param("openImpersonate", "true"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("openImpersonate", true))
        .andExpect(content().string(containsString(IMPERSONATE_AUTO_OPEN_ATTRIBUTE)));
  }

  @Test
  void withoutTheQueryParamTheImpersonateDialogDoesNotAutoOpen() throws Exception {
    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(model().attribute("openImpersonate", false))
        .andExpect(content().string(not(containsString(IMPERSONATE_AUTO_OPEN_ATTRIBUTE))));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsUnknownOrNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
  }
}

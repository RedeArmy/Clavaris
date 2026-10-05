package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.clavaris.identity.application.usecase.getloginactivityforaccount.GetLoginActivityForAccountUseCase;
import com.clavaris.identity.application.usecase.impersonateaccount.OAuthClientsForOrganizationProvider;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ListActiveSessionsForAccountUseCase;
import com.clavaris.identity.application.usecase.listoauthgrantsforaccount.ListOAuthGrantsForAccountUseCase;
import com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount.ListWebAuthnCredentialsForAccountUseCase;
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
  private GetLoginActivityForAccountUseCase getLoginActivity;
  private ListWebAuthnCredentialsForAccountUseCase listWebAuthnCredentials;
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
    getLoginActivity = mock(GetLoginActivityForAccountUseCase.class);
    listWebAuthnCredentials = mock(ListWebAuthnCredentialsForAccountUseCase.class);
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
    when(getLoginActivity.handle(any())).thenReturn(List.of());
    when(listWebAuthnCredentials.handle(any())).thenReturn(List.of());

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
                    getLoginActivity,
                    listWebAuthnCredentials,
                    organizationResolver,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/" + organizationId + "/users/" + account.id().value();
  }

  // Live bug, 2026-09-22: this exact page (the "View Profile" destination reached from the Users
  // tab 3-dot menu) previously loaded neither htmx.min.js nor organization-dialog.js — the
  // shared sidebar's own "Manage account" trigger opened its dialog (native <dialog> markup,
  // JS-independent) but stuck on "Loading..." forever, since htmx was never loaded to actually
  // fetch the content. Both scripts now load from inside dashboard-nav.html itself — asserting
  // they're present here locks that fix in, not just documents it.
  @Test
  void sidebarLoadsBothScriptsManageAccountNeeds() throws Exception {
    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/js/htmx.min.js")))
        .andExpect(content().string(containsString("/js/organization-dialog.js")));
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
  void showsABackLinkToTheUsersList() throws Exception {
    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("clavaris-back-button")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "href=\"/platform/dashboard/organizations/"
                            + organizationId
                            + "/users\"")));
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

  // TD-FUT-034, Clerk "View Profile" activity heatmap parity: the controller hands the template a
  // LoginActivityView built from the use case's day counts (the grid itself is covered by
  // LoginActivityGridTest), and the card shows the headline numbers.
  @Test
  void buildsTheActivityViewFromTheUseCasesDayCounts() throws Exception {
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
    when(getLoginActivity.handle(any()))
        .thenReturn(
            List.of(
                new com.clavaris.identity.application.usecase.getloginactivityforaccount
                    .LoginActivityDay(today, 2)));

    org.springframework.test.web.servlet.MvcResult result =
        mockMvc
            .perform(get(path()))
            .andExpect(status().isOk())
            .andExpect(
                content()
                    .string(
                        containsString(
                            "Sign-in activity over the last 365 days: 2 sign-ins on 1 day")))
            .andExpect(content().string(containsString("Monthly totals")))
            .andReturn();

    LoginActivityView view =
        (LoginActivityView) result.getModelAndView().getModel().get("loginActivity");
    assertThat(view.totalSignIns()).isEqualTo(2);
    assertThat(view.activeDays()).isEqualTo(1);
  }

  @Test
  void anAccountWithNoSignInsShowsAnEmptyStateInsteadOfABlankGrid() throws Exception {
    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "No sign-ins have been recorded for this user in the last 365 days")))
        .andExpect(content().string(not(containsString("clavaris-heatmap__week"))));
  }

  // Account.lastSignedInAt and the sign-in history are separate records: an account that signed in
  // before history was kept (or over a year ago) says so instead of reading as "never signed in".
  @Test
  void anAccountThatSignedInButHasNoHistoryExplainsTheGap() throws Exception {
    account.recordSignIn();

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("This user last signed in on")))
        .andExpect(content().string(containsString("but no sign-in history is recorded")))
        .andExpect(
            content().string(not(containsString("No sign-ins have been recorded for this user"))));
  }

  // Profile picture card: choose first, upload second; the one row of actions has Remove only when
  // there is a picture to remove.
  @Test
  void theProfilePictureCardChoosesThenUploadsAndOffersNoRemoveWithoutAPicture() throws Exception {
    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-picture-upload")))
        .andExpect(content().string(containsString("Choose image")))
        .andExpect(content().string(containsString("data-picture-submit")))
        .andExpect(content().string(containsString("/js/profile-picture-upload.js")))
        .andExpect(content().string(not(containsString("/picture/remove"))));
  }

  @Test
  void removeIsOfferedWithAConfirmationOnlyWhenTheAccountHasAPicture() throws Exception {
    Account withPicture = org.mockito.Mockito.spy(account);
    org.mockito.Mockito.doReturn(Optional.of("avatars/ada.png")).when(withPicture).pictureUrl();
    when(getAccount.handle(any())).thenReturn(Optional.of(withPicture));

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/picture/remove")))
        .andExpect(content().string(containsString("Remove the profile picture?")));
  }
}

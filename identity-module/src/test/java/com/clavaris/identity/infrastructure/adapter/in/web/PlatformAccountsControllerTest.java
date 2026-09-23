package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.identity.application.usecase.admincreateaccountfororganization.AdminCreateAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.listaccountsfororganization.ListAccountsForOrganizationUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccessRestrictedException;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.time.Instant;
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

/** Same standalone MockMvc + real Thymeleaf setup as {@code PlatformSigningKeyControllerTest}. */
class PlatformAccountsControllerTest {

  private static final PlatformAccountId OWNER_ID = PlatformAccountId.newId();

  private ListAccountsForOrganizationUseCase listAccounts;
  private AdminCreateAccountForOrganizationUseCase createAccount;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    listAccounts = mock(ListAccountsForOrganizationUseCase.class);
    createAccount = mock(AdminCreateAccountForOrganizationUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(listAccounts.handle(any())).thenReturn(emptyPage());

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
                new PlatformAccountsController(
                    listAccounts, createAccount, organizationResolver, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String basePath() {
    return "/platform/dashboard/organizations/" + organizationId + "/users";
  }

  private Account sampleAccount() {
    return Account.register(
        new OrganizationId(organizationId), new Email("ada@example.com"), "Ada", "Lovelace", null);
  }

  private static KeysetPage<Account> emptyPage() {
    return new KeysetPage<>(List.of(), null, null, false, false);
  }

  private static KeysetCursor cursorOf(final Account account) {
    return new KeysetCursor(account.createdAt(), account.id().value());
  }

  // Live bug, 2026-09-22: the shared sidebar's own "Manage account" trigger needs htmx.min.js
  // and organization-dialog.js unconditionally, but every page used to opt into loading them
  // independently based only on its own content's needs — this page happened to have neither
  // missing, but sibling pages did (account-profile.html, account-sessions.html,
  // account-audit-log.html, organization-danger-zone.html), silently breaking "Manage account"
  // there. Both scripts now load from inside dashboard-nav.html itself (identity-module's own
  // copy) — asserting they're present here locks that fix in, not just documents it.
  @Test
  void sidebarLoadsBothScriptsManageAccountNeeds() throws Exception {
    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/js/htmx.min.js")))
        .andExpect(content().string(containsString("/js/organization-dialog.js")));
  }

  @Test
  void showsTheOrganizationsUsers() throws Exception {
    Account account = sampleAccount();
    KeysetCursor cursor = cursorOf(account);
    when(listAccounts.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(account), cursor, cursor, false, false));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/organization-users"))
        .andExpect(model().attribute("organizationName", "Acme Co"))
        .andExpect(model().attribute("users", List.of(account)));
  }

  // Live-found bug, 2026-09-20: every row-menu action (View profile, View log, Impersonate,
  // Lock/Ban/Delete) built its URL from ${account.id()} instead of ${account.id().value()} —
  // AccountId's own record toString (no override) renders as "AccountId[value=...]", which
  // Thymeleaf then URL-encodes into the path segment verbatim. No prior test caught this because
  // Thymeleaf renders that malformed URL without error; only a real click (a real UUID path
  // segment, not this) tells the two apart. Asserting the rendered href/action content directly,
  // not just status/view/model, so this class of bug fails a test instead of only surfacing live.
  @Test
  void rowMenuActionsLinkToTheRawAccountIdNotItsRecordToString() throws Exception {
    Account account = sampleAccount();
    KeysetCursor cursor = cursorOf(account);
    when(listAccounts.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(account), cursor, cursor, false, false));

    String rawId = account.id().value().toString();
    String usersPath = basePath() + "/" + rawId;

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("AccountId[value="))))
        .andExpect(content().string(containsString(usersPath)))
        .andExpect(content().string(containsString(usersPath + "/audit-log")))
        .andExpect(content().string(containsString(usersPath + "/suspend")))
        .andExpect(content().string(containsString(usersPath + "/ban")))
        .andExpect(content().string(containsString(usersPath + "/delete")));
  }

  @Test
  void showsABackLinkToTheOrganization() throws Exception {
    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("clavaris-back-link")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "href=\"/platform/dashboard/organizations/" + organizationId + "\"")));
  }

  @Test
  void htmxGetReturnsTheUsersFragmentInsteadOfTheFullPage() throws Exception {
    mockMvc
        .perform(get(basePath()).header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/organization-users :: users"));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath())).andExpect(status().isNotFound());
  }

  @Test
  void validCreatePostRedirectsOnSuccess() throws Exception {
    when(createAccount.handle(any())).thenReturn(new AccountId(UUID.randomUUID()));

    mockMvc
        .perform(
            post(basePath())
                .param("email", "new-user@example.com")
                .param("password", "a-valid-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath()));

    verify(createAccount).handle(any());
  }

  @Test
  void createWithNoEmailRendersAnErrorWithoutCreatingAnything() throws Exception {
    mockMvc
        .perform(post(basePath()).param("password", "a-valid-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/organization-users"));

    verify(createAccount, never()).handle(any());
  }

  @Test
  void createRendersAFieldErrorWhenTheEmailIsAlreadyRegistered() throws Exception {
    doThrow(new EmailAlreadyRegisteredException(new OrganizationId(organizationId)))
        .when(createAccount)
        .handle(any());

    mockMvc
        .perform(
            post(basePath())
                .param("email", "taken@example.com")
                .param("password", "a-valid-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/organization-users"))
        .andExpect(model().attributeHasFieldErrors("createForm", "email"));
  }

  @Test
  void createRendersAFieldErrorWhenTheUsernameIsAlreadyTaken() throws Exception {
    doThrow(new UsernameAlreadyRegisteredException(new OrganizationId(organizationId)))
        .when(createAccount)
        .handle(any());

    mockMvc
        .perform(
            post(basePath())
                .param("email", "new-user@example.com")
                .param("username", "taken")
                .param("password", "a-valid-password"))
        .andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("createForm", "username"));
  }

  @Test
  void createRendersAFieldErrorWhenThePasswordIsWeak() throws Exception {
    doThrow(new WeakPasswordException()).when(createAccount).handle(any());

    mockMvc
        .perform(post(basePath()).param("email", "new-user@example.com").param("password", "short"))
        .andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("createForm", "password"));
  }

  @Test
  void createRendersAFieldErrorWhenAccessIsRestricted() throws Exception {
    doThrow(new AccessRestrictedException()).when(createAccount).handle(any());

    mockMvc
        .perform(
            post(basePath())
                .param("email", "blocked@example.com")
                .param("password", "a-valid-password"))
        .andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("createForm", "email"));
  }

  @Test
  void passesTheAfterCursorThroughToTheUseCase() throws Exception {
    KeysetCursor cursor = new KeysetCursor(Instant.now(), UUID.randomUUID());

    mockMvc.perform(get(basePath()).param("after", cursor.encode()));

    verify(listAccounts).handle(any());
  }

  // SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab 3-dot row menu: proves the template's
  // own Lock/Unlock and Ban/Unban toggle-by-status branching renders without error for every real
  // AccountStatus this menu has to handle, not just the ACTIVE default every other test here uses.
  @Test
  void rowMenuOffersUnlockInsteadOfLockForASuspendedAccount() throws Exception {
    Account suspended = sampleAccount();
    suspended.suspend();
    KeysetCursor cursor = cursorOf(suspended);
    when(listAccounts.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(suspended), cursor, cursor, false, false));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Unlock account")))
        .andExpect(content().string(not(containsString("Lock account"))));
  }

  @Test
  void rowMenuOffersUnbanInsteadOfBanForABannedAccount() throws Exception {
    Account banned = sampleAccount();
    banned.ban();
    KeysetCursor cursor = cursorOf(banned);
    when(listAccounts.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(banned), cursor, cursor, false, false));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Unban account")))
        .andExpect(content().string(not(containsString("Ban account"))));
  }
}

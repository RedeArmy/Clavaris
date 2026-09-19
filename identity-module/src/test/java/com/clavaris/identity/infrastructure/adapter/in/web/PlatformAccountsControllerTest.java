package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}

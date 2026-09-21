package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.deleteownaccount.DeleteOwnAccountUseCase;
import com.clavaris.identity.application.usecase.deleteownaccount.SelfDeleteNotAllowedException;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.removeaccountprofilepicture.RemoveAccountProfilePictureCommand;
import com.clavaris.identity.application.usecase.removeaccountprofilepicture.RemoveAccountProfilePictureUseCase;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.InvalidProfilePictureException;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.UpdateAccountProfilePictureUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc + real Thymeleaf setup as {@link AccountSessionsControllerTest}. */
class AccountProfileControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private GetAccountForOrganizationUseCase getAccount;
  private UpdateAccountProfilePictureUseCase updatePicture;
  private RemoveAccountProfilePictureUseCase removePicture;
  private DeleteOwnAccountUseCase deleteOwnAccount;
  private CurrentAccountResolver currentAccount;
  private Account account;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    getAccount = mock(GetAccountForOrganizationUseCase.class);
    updatePicture = mock(UpdateAccountProfilePictureUseCase.class);
    removePicture = mock(RemoveAccountProfilePictureUseCase.class);
    deleteOwnAccount = mock(DeleteOwnAccountUseCase.class);
    currentAccount = mock(CurrentAccountResolver.class);
    account =
        Account.reconstitute(
            AccountId.newId(),
            new OrganizationId(ORGANIZATION_ID),
            new Email("ada@example.com"),
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
            null,
            null,
            null);
    when(currentAccount.resolve(any())).thenReturn(Optional.of(account.id()));
    when(getAccount.handle(any())).thenReturn(Optional.of(account));

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
                new AccountProfileController(
                    getAccount, updatePicture, removePicture, deleteOwnAccount, currentAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getRendersTheCurrentAccountsProfile() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/account/profile", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/profile"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ada@example.com")));
  }

  @Test
  void postPictureUploadsAndRedirects() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "avatar.png", "image/png", new byte[] {1, 2, 3});

    mockMvc
        .perform(
            multipart("/o/{organizationId}/account/profile/picture", ORGANIZATION_ID).file(file))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/account/profile?updated"));

    verify(updatePicture).handle(any());
  }

  @Test
  void postPictureReRendersWithAnErrorOnInvalidUpload() throws Exception {
    doThrow(new InvalidProfilePictureException("Unsupported image type: image/svg+xml"))
        .when(updatePicture)
        .handle(any());
    MockMultipartFile file =
        new MockMultipartFile("file", "avatar.svg", "image/svg+xml", new byte[] {1});

    mockMvc
        .perform(
            multipart("/o/{organizationId}/account/profile/picture", ORGANIZATION_ID).file(file))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/profile"))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Unsupported image type")));
  }

  @Test
  void postRemoveDelegatesAndRedirects() throws Exception {
    mockMvc
        .perform(post("/o/{organizationId}/account/profile/picture/remove", ORGANIZATION_ID))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/account/profile?removed"));

    verify(removePicture).handle(new RemoveAccountProfilePictureCommand(account.id()));
  }

  @Test
  void getRendersDeleteAccountSectionOnlyWhenAllowed() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/account/profile", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("delete-account-dialog"))));

    account.allowSelfDelete();

    mockMvc
        .perform(get("/o/{organizationId}/account/profile", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("delete-account-dialog")));
  }

  @Test
  void postDeleteInvalidatesSessionAndRedirectsToLoginWhenAllowed() throws Exception {
    mockMvc
        .perform(post("/o/{organizationId}/account/profile/delete", ORGANIZATION_ID))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login?accountDeleted"));

    verify(deleteOwnAccount).handle(account.id());
  }

  @Test
  void postDeleteReRendersWithAnErrorWhenNotAllowed() throws Exception {
    doThrow(new SelfDeleteNotAllowedException(account.id())).when(deleteOwnAccount).handle(any());

    mockMvc
        .perform(post("/o/{organizationId}/account/profile/delete", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/profile"));
  }
}

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

import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ListActiveSessionsForPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.listconnectedaccountsforplatformaccount.ListConnectedAccountsForPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.removeplatformaccountprofilepicture.RemovePlatformAccountProfilePictureUseCase;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.InvalidProfilePictureException;
import com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture.UpdatePlatformAccountProfilePictureUseCase;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc + real Thymeleaf setup as {@link AccountProfileControllerTest}. */
class PlatformAccountProfileControllerTest {

  private PlatformAccountRepository accounts;
  private ListConnectedAccountsForPlatformAccountUseCase listConnectedAccounts;
  private ListActiveSessionsForPlatformAccountUseCase listSessions;
  private UpdatePlatformAccountProfilePictureUseCase updatePicture;
  private RemovePlatformAccountProfilePictureUseCase removePicture;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private PlatformAccount account;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    accounts = mock(PlatformAccountRepository.class);
    listConnectedAccounts = mock(ListConnectedAccountsForPlatformAccountUseCase.class);
    listSessions = mock(ListActiveSessionsForPlatformAccountUseCase.class);
    updatePicture = mock(UpdatePlatformAccountProfilePictureUseCase.class);
    removePicture = mock(RemovePlatformAccountProfilePictureUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);
    account =
        PlatformAccount.reconstitute(
            com.clavaris.identity.domain.model.PlatformAccountId.newId(),
            new Email("operator@example.com"),
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
            null);
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(account.id()));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    when(listConnectedAccounts.handle(any())).thenReturn(List.of());
    when(listSessions.handle(any())).thenReturn(List.of());

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
                new PlatformAccountProfileController(
                    accounts,
                    listConnectedAccounts,
                    listSessions,
                    updatePicture,
                    removePicture,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getRendersTheFullPageWithTheCurrentAccountsProfile() throws Exception {
    mockMvc
        .perform(get("/platform/account"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/manage-account"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("operator@example.com")));
  }

  @Test
  void getWithHxRequestHeaderRendersJustTheFragment() throws Exception {
    mockMvc
        .perform(get("/platform/account").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/manage-account :: content"));
  }

  @Test
  void postProfileUpdatesNameAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/platform/account/profile")
                .param("firstName", "Ada")
                .param("lastName", "Lovelace"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/account?updated"));

    org.assertj.core.api.Assertions.assertThat(account.firstName()).contains("Ada");
    org.assertj.core.api.Assertions.assertThat(account.lastName()).contains("Lovelace");
    verify(accounts).save(account);
  }

  @Test
  void postPictureUploadsAndRedirects() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "avatar.png", "image/png", new byte[] {1, 2, 3});

    mockMvc
        .perform(multipart("/platform/account/picture").file(file))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/account?updated"));

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
        .perform(multipart("/platform/account/picture").file(file))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/manage-account"))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Unsupported image type")));
  }

  @Test
  void postRemoveDelegatesAndRedirects() throws Exception {
    mockMvc
        .perform(post("/platform/account/picture/remove"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/account?removed"));

    verify(removePicture).handle(account.id());
  }
}

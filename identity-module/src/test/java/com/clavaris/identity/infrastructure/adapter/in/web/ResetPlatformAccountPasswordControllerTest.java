package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.ConfirmPlatformAccountPasswordResetCommand;
import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.ConfirmPlatformAccountPasswordResetUseCase;
import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.InvalidVerificationTokenException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc setup rationale as {@code RegisterAccountControllerTest}. */
class ResetPlatformAccountPasswordControllerTest {

  private ConfirmPlatformAccountPasswordResetUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ConfirmPlatformAccountPasswordResetUseCase.class);

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
        MockMvcBuilders.standaloneSetup(new ResetPlatformAccountPasswordController(useCase))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getShowsTheFormWithTheTokenCarriedFromTheQueryParameter() throws Exception {
    mockMvc
        .perform(get("/platform/reset-password").param("token", "a-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/reset-password"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void validSubmissionResetsAndRedirectsToSuccess() throws Exception {
    mockMvc
        .perform(
            post("/platform/reset-password")
                .param("token", "a-token")
                .param("newPassword", "a-Str0ng-Password!")
                .param("confirmPassword", "a-Str0ng-Password!"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/reset-password/success"));

    verify(useCase)
        .handle(new ConfirmPlatformAccountPasswordResetCommand("a-token", "a-Str0ng-Password!"));
  }

  @Test
  void mismatchedConfirmationRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/platform/reset-password")
                .param("token", "a-token")
                .param("newPassword", "a-Str0ng-Password!")
                .param("confirmPassword", "a-different-one!"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/reset-password"))
        .andExpect(model().attributeHasFieldErrors("form", "confirmPassword"));

    verifyNoInteractions(useCase);
  }

  @Test
  void anInvalidOrExpiredTokenRendersTheInvalidLinkPage() throws Exception {
    doThrow(new InvalidVerificationTokenException())
        .when(useCase)
        .handle(new ConfirmPlatformAccountPasswordResetCommand("bad-token", "a-Str0ng-Password!"));

    mockMvc
        .perform(
            post("/platform/reset-password")
                .param("token", "bad-token")
                .param("newPassword", "a-Str0ng-Password!")
                .param("confirmPassword", "a-Str0ng-Password!"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/verification-link-invalid"));
  }

  @Test
  void aWeakPasswordRejectedByTheUseCaseRerendersTheFormWithAFieldError() throws Exception {
    doThrow(new WeakPasswordException())
        .when(useCase)
        .handle(new ConfirmPlatformAccountPasswordResetCommand("a-token", "aaaaaaaa"));

    mockMvc
        .perform(
            post("/platform/reset-password")
                .param("token", "a-token")
                .param("newPassword", "aaaaaaaa")
                .param("confirmPassword", "aaaaaaaa"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/reset-password"))
        .andExpect(model().attributeHasFieldErrors("form", "newPassword"));
  }

  @Test
  void successPageRenders() throws Exception {
    mockMvc
        .perform(get("/platform/reset-password/success"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/reset-password-success"));
  }
}

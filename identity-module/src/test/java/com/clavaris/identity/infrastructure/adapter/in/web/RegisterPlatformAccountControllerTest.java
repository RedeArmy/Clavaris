package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountEmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registerplatformaccount.RegisterPlatformAccountCommand;
import com.clavaris.identity.application.usecase.registerplatformaccount.RegisterPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.RequestPlatformAccountEmailVerificationCommand;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.RequestPlatformAccountEmailVerificationUseCase;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccountId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc setup rationale as {@code RegisterAccountControllerTest}. */
class RegisterPlatformAccountControllerTest {

  private RegisterPlatformAccountUseCase useCase;
  private RequestPlatformAccountEmailVerificationUseCase requestEmailVerification;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(RegisterPlatformAccountUseCase.class);
    requestEmailVerification = mock(RequestPlatformAccountEmailVerificationUseCase.class);

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
                new RegisterPlatformAccountController(useCase, requestEmailVerification))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getShowsTheRegistrationForm() throws Exception {
    mockMvc
        .perform(get("/platform/register"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/register"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void validSubmissionRegistersTriggersVerificationEmailAndRedirectsToPendingVerification()
      throws Exception {
    PlatformAccountId accountId = PlatformAccountId.newId();
    when(useCase.handle(any())).thenReturn(accountId);

    mockMvc
        .perform(
            post("/platform/register")
                .param("email", "founder@example.com")
                .param("password", "a-valid-password")
                .param("confirmPassword", "a-valid-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/register/pending-verification"));

    verify(useCase)
        .handle(
            new RegisterPlatformAccountCommand(
                new Email("founder@example.com"), "a-valid-password"));
    verify(requestEmailVerification)
        .handle(new RequestPlatformAccountEmailVerificationCommand(accountId));
  }

  @Test
  void invalidEmailRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/platform/register")
                .param("email", "not-an-email")
                .param("password", "a-valid-password")
                .param("confirmPassword", "a-valid-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/register"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(useCase);
    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void mismatchedConfirmationRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/platform/register")
                .param("email", "founder@example.com")
                .param("password", "a-valid-password")
                .param("confirmPassword", "a-different-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/register"))
        .andExpect(model().attributeHasFieldErrors("form", "confirmPassword"));

    verifyNoInteractions(useCase);
    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void alreadyRegisteredEmailRerendersTheFormWithAFieldError() throws Exception {
    when(useCase.handle(any())).thenThrow(new PlatformAccountEmailAlreadyRegisteredException());

    mockMvc
        .perform(
            post("/platform/register")
                .param("email", "taken@example.com")
                .param("password", "a-valid-password")
                .param("confirmPassword", "a-valid-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/register"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void weakPasswordRejectedByTheUseCaseRerendersTheFormWithAFieldError() throws Exception {
    when(useCase.handle(any())).thenThrow(new WeakPasswordException());

    mockMvc
        .perform(
            post("/platform/register")
                .param("email", "founder@example.com")
                .param("password", "aaaaaaaa")
                .param("confirmPassword", "aaaaaaaa"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/register"))
        .andExpect(model().attributeHasFieldErrors("form", "password"));

    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void pendingVerificationPageRenders() throws Exception {
    mockMvc
        .perform(get("/platform/register/pending-verification"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/register-pending-verification"));
  }
}

package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException;
import com.clavaris.identity.application.usecase.confirmemailverificationcode.ConfirmEmailVerificationCodeCommand;
import com.clavaris.identity.application.usecase.confirmemailverificationcode.ConfirmEmailVerificationCodeUseCase;
import com.clavaris.identity.application.usecase.confirmemailverificationcode.InvalidVerificationCodeException;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc setup rationale as {@code RegisterAccountControllerTest}. */
class VerifyEmailCodeControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private ConfirmEmailVerificationCodeUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ConfirmEmailVerificationCodeUseCase.class);

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
        MockMvcBuilders.standaloneSetup(new VerifyEmailCodeController(useCase))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void theFormRendersWithTheAddressPrefilledWhenCarried() throws Exception {
    mockMvc
        .perform(
            get("/o/{organizationId}/verify-email/code", ORGANIZATION_ID)
                .param("email", "new-user@example.com"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/verify-email-code"));

    verifyNoInteractions(useCase);
  }

  @Test
  void aRightCodeVerifiesAndRendersSuccess() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/verify-email/code", ORGANIZATION_ID)
                .param("email", "new-user@example.com")
                .param("code", "K7M2QX"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/verify-email-success"));

    verify(useCase)
        .handle(
            new ConfirmEmailVerificationCodeCommand(
                new OrganizationId(ORGANIZATION_ID), new Email("new-user@example.com"), "K7M2QX"));
  }

  @Test
  void aWrongCodeRerendersTheFormWithAnError() throws Exception {
    doThrow(new InvalidVerificationCodeException()).when(useCase).handle(any());

    mockMvc
        .perform(
            post("/o/{organizationId}/verify-email/code", ORGANIZATION_ID)
                .param("email", "new-user@example.com")
                .param("code", "AAAAAA"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/verify-email-code"))
        .andExpect(model().attribute("codeError", true));
  }

  @Test
  void aPausedAddressRerendersTheFormTellingThePersonToWait() throws Exception {
    doThrow(new CodeTemporarilyLockedException()).when(useCase).handle(any());

    mockMvc
        .perform(
            post("/o/{organizationId}/verify-email/code", ORGANIZATION_ID)
                .param("email", "new-user@example.com")
                .param("code", "K7M2QX"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/verify-email-code"))
        .andExpect(model().attribute("codeLocked", true));
  }

  @Test
  void aBlankCodeRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/verify-email/code", ORGANIZATION_ID)
                .param("email", "new-user@example.com")
                .param("code", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/verify-email-code"));

    verifyNoInteractions(useCase);
  }
}

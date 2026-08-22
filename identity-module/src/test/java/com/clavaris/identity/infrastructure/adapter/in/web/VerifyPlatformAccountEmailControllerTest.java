package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.confirmplatformaccountemailverification.ConfirmPlatformAccountEmailVerificationCommand;
import com.clavaris.identity.application.usecase.confirmplatformaccountemailverification.ConfirmPlatformAccountEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.confirmplatformaccountemailverification.InvalidVerificationTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc setup rationale as {@code RegisterAccountControllerTest}. */
class VerifyPlatformAccountEmailControllerTest {

  private ConfirmPlatformAccountEmailVerificationUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ConfirmPlatformAccountEmailVerificationUseCase.class);

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
        MockMvcBuilders.standaloneSetup(new VerifyPlatformAccountEmailController(useCase))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void aValidTokenConfirmsAndRendersSuccess() throws Exception {
    mockMvc
        .perform(get("/platform/verify-email").param("token", "a-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/verify-email-success"));

    verify(useCase).handle(new ConfirmPlatformAccountEmailVerificationCommand("a-token"));
  }

  @Test
  void anInvalidTokenRendersTheInvalidLinkPage() throws Exception {
    doThrow(new InvalidVerificationTokenException())
        .when(useCase)
        .handle(new ConfirmPlatformAccountEmailVerificationCommand("bad-token"));

    mockMvc
        .perform(get("/platform/verify-email").param("token", "bad-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/verification-link-invalid"));
  }
}

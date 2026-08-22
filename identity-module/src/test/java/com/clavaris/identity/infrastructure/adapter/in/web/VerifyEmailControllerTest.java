package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.confirmemailverification.ConfirmEmailVerificationCommand;
import com.clavaris.identity.application.usecase.confirmemailverification.ConfirmEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.confirmemailverification.InvalidVerificationTokenException;
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
class VerifyEmailControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private ConfirmEmailVerificationUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ConfirmEmailVerificationUseCase.class);

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
        MockMvcBuilders.standaloneSetup(new VerifyEmailController(useCase))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void aValidTokenConfirmsAndRendersSuccess() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/verify-email", ORGANIZATION_ID).param("token", "a-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/verify-email-success"));

    verify(useCase).handle(new ConfirmEmailVerificationCommand("a-token"));
  }

  @Test
  void anInvalidTokenRendersTheInvalidLinkPage() throws Exception {
    doThrow(new InvalidVerificationTokenException())
        .when(useCase)
        .handle(eq(new ConfirmEmailVerificationCommand("bad-token")));

    mockMvc
        .perform(
            get("/o/{organizationId}/verify-email", ORGANIZATION_ID).param("token", "bad-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/verification-link-invalid"));
  }
}

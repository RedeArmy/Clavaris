package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetCommand;
import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetUseCase;
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
class ForgotPasswordControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private RequestPasswordResetUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(RequestPasswordResetUseCase.class);

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
        MockMvcBuilders.standaloneSetup(new ForgotPasswordController(useCase))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getShowsTheForm() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/forgot-password", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/forgot-password"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void validSubmissionAlwaysRedirectsToThePendingPage() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/forgot-password", ORGANIZATION_ID)
                .param("email", "someone@example.com"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/forgot-password/pending"));

    verify(useCase)
        .handle(
            new RequestPasswordResetCommand(
                new OrganizationId(ORGANIZATION_ID), new Email("someone@example.com")));
  }

  @Test
  void invalidEmailRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/forgot-password", ORGANIZATION_ID)
                .param("email", "not-an-email"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/forgot-password"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(useCase);
  }

  @Test
  void pendingPageRenders() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/forgot-password/pending", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/forgot-password-pending"));
  }
}

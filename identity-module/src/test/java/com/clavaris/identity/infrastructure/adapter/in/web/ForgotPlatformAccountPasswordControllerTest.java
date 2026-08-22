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

import com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset.RequestPlatformAccountPasswordResetCommand;
import com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset.RequestPlatformAccountPasswordResetUseCase;
import com.clavaris.identity.domain.model.Email;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc setup rationale as {@code RegisterAccountControllerTest}. */
class ForgotPlatformAccountPasswordControllerTest {

  private RequestPlatformAccountPasswordResetUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(RequestPlatformAccountPasswordResetUseCase.class);

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
        MockMvcBuilders.standaloneSetup(new ForgotPlatformAccountPasswordController(useCase))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getShowsTheForm() throws Exception {
    mockMvc
        .perform(get("/platform/forgot-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/forgot-password"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void validSubmissionAlwaysRedirectsToThePendingPage() throws Exception {
    mockMvc
        .perform(post("/platform/forgot-password").param("email", "founder@example.com"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/forgot-password/pending"));

    verify(useCase)
        .handle(new RequestPlatformAccountPasswordResetCommand(new Email("founder@example.com")));
  }

  @Test
  void invalidEmailRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(post("/platform/forgot-password").param("email", "not-an-email"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/forgot-password"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(useCase);
  }

  @Test
  void pendingPageRenders() throws Exception {
    mockMvc
        .perform(get("/platform/forgot-password/pending"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/forgot-password-pending"));
  }
}

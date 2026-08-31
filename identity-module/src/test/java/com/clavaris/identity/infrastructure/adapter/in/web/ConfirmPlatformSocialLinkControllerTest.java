package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.confirmpendingplatformsociallink.ConfirmPendingPlatformSocialLinkCommand;
import com.clavaris.identity.application.usecase.confirmpendingplatformsociallink.ConfirmPendingPlatformSocialLinkUseCase;
import com.clavaris.identity.application.usecase.confirmpendingplatformsociallink.InvalidPendingPlatformSocialLinkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc setup rationale as {@code ConfirmSocialLinkControllerTest}. */
class ConfirmPlatformSocialLinkControllerTest {

  private ConfirmPendingPlatformSocialLinkUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ConfirmPendingPlatformSocialLinkUseCase.class);

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
        MockMvcBuilders.standaloneSetup(new ConfirmPlatformSocialLinkController(useCase))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void aValidTokenConfirmsAndRendersSuccess() throws Exception {
    mockMvc
        .perform(get("/platform/confirm-social-link").param("token", "a-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/social-link-confirmed"));

    verify(useCase).handle(new ConfirmPendingPlatformSocialLinkCommand("a-token"));
  }

  @Test
  void anInvalidTokenRendersTheInvalidLinkPage() throws Exception {
    doThrow(new InvalidPendingPlatformSocialLinkException())
        .when(useCase)
        .handle(new ConfirmPendingPlatformSocialLinkCommand("bad-token"));

    mockMvc
        .perform(get("/platform/confirm-social-link").param("token", "bad-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/social-link-invalid"));
  }
}

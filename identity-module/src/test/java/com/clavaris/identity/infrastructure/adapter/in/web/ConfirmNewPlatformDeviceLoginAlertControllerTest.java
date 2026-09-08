package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert.ConfirmNewPlatformDeviceLoginAlertCommand;
import com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert.ConfirmNewPlatformDeviceLoginAlertUseCase;
import com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert.InvalidNewPlatformDeviceLoginAlertException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Same standalone MockMvc setup rationale as {@code ResetPlatformAccountPasswordController}'s own
 * {@code ResetPasswordControllerTest}-shaped sibling.
 */
class ConfirmNewPlatformDeviceLoginAlertControllerTest {

  private ConfirmNewPlatformDeviceLoginAlertUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ConfirmNewPlatformDeviceLoginAlertUseCase.class);

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
        MockMvcBuilders.standaloneSetup(new ConfirmNewPlatformDeviceLoginAlertController(useCase))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getShowsTheConfirmFormWithTheTokenCarriedFromTheQueryParameter() throws Exception {
    mockMvc
        .perform(get("/platform/account-alert/lock").param("token", "a-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account-alert-lock-confirm"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void validSubmissionLocksTheAccountAndRedirectsToSuccess() throws Exception {
    mockMvc
        .perform(post("/platform/account-alert/lock").param("token", "a-token"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/account-alert/lock/success"));

    verify(useCase).handle(new ConfirmNewPlatformDeviceLoginAlertCommand("a-token"));
  }

  @Test
  void anInvalidOrExpiredTokenRendersTheInvalidLinkPage() throws Exception {
    doThrow(new InvalidNewPlatformDeviceLoginAlertException())
        .when(useCase)
        .handle(new ConfirmNewPlatformDeviceLoginAlertCommand("bad-token"));

    mockMvc
        .perform(post("/platform/account-alert/lock").param("token", "bad-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/verification-link-invalid"));
  }

  @Test
  void aBlankTokenRerendersTheInvalidLinkPageWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(post("/platform/account-alert/lock").param("token", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/verification-link-invalid"));
  }

  @Test
  void getSuccessRendersTheSuccessPage() throws Exception {
    mockMvc
        .perform(get("/platform/account-alert/lock/success"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/account-alert-lock-success"));
  }
}

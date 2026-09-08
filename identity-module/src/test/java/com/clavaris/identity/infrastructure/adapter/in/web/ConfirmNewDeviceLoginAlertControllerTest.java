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

import com.clavaris.identity.application.usecase.confirmnewdeviceloginalert.ConfirmNewDeviceLoginAlertCommand;
import com.clavaris.identity.application.usecase.confirmnewdeviceloginalert.ConfirmNewDeviceLoginAlertUseCase;
import com.clavaris.identity.application.usecase.confirmnewdeviceloginalert.InvalidNewDeviceLoginAlertException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc setup rationale as {@code ResetPasswordControllerTest}. */
class ConfirmNewDeviceLoginAlertControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private ConfirmNewDeviceLoginAlertUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ConfirmNewDeviceLoginAlertUseCase.class);

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
        MockMvcBuilders.standaloneSetup(new ConfirmNewDeviceLoginAlertController(useCase))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getShowsTheConfirmFormWithTheTokenCarriedFromTheQueryParameter() throws Exception {
    mockMvc
        .perform(
            get("/o/{organizationId}/account-alert/lock", ORGANIZATION_ID)
                .param("token", "a-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account-alert-lock-confirm"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void validSubmissionLocksTheAccountAndRedirectsToSuccess() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/account-alert/lock", ORGANIZATION_ID)
                .param("token", "a-token"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/account-alert/lock/success"));

    verify(useCase).handle(new ConfirmNewDeviceLoginAlertCommand("a-token"));
  }

  @Test
  void anInvalidOrExpiredTokenRendersTheInvalidLinkPage() throws Exception {
    doThrow(new InvalidNewDeviceLoginAlertException())
        .when(useCase)
        .handle(new ConfirmNewDeviceLoginAlertCommand("bad-token"));

    mockMvc
        .perform(
            post("/o/{organizationId}/account-alert/lock", ORGANIZATION_ID)
                .param("token", "bad-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/verification-link-invalid"));
  }

  @Test
  void aBlankTokenRerendersTheInvalidLinkPageWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(post("/o/{organizationId}/account-alert/lock", ORGANIZATION_ID).param("token", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/verification-link-invalid"));
  }

  @Test
  void getSuccessRendersTheSuccessPage() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/account-alert/lock/success", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account-alert-lock-success"));
  }
}

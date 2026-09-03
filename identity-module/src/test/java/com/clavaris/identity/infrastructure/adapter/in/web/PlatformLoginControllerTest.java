package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword.AuthenticatePlatformAccountWithPasswordCommand;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword.AuthenticatePlatformAccountWithPasswordUseCase;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword.InvalidPlatformCredentialsException;
import com.clavaris.identity.application.usecase.recordplatformaccountlogindevice.RecordPlatformAccountLoginDeviceUseCase;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc setup rationale as {@code RegisterAccountControllerTest}. */
class PlatformLoginControllerTest {

  private AuthenticatePlatformAccountWithPasswordUseCase useCase;
  private PlatformAuthenticatedSessionEstablisher sessionEstablisher;
  private RecordPlatformAccountLoginDeviceUseCase recordLoginDevice;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(AuthenticatePlatformAccountWithPasswordUseCase.class);
    sessionEstablisher = mock(PlatformAuthenticatedSessionEstablisher.class);
    recordLoginDevice = mock(RecordPlatformAccountLoginDeviceUseCase.class);
    when(recordLoginDevice.handle(any())).thenReturn(Optional.empty());

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
                new PlatformLoginController(useCase, sessionEstablisher, recordLoginDevice))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getShowsTheLoginForm() throws Exception {
    mockMvc
        .perform(get("/platform/login"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/login"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void validCredentialsEstablishASessionAndRedirectToWhatItReturns() throws Exception {
    PlatformAccountId accountId = PlatformAccountId.newId();
    when(useCase.handle(any())).thenReturn(accountId);
    when(sessionEstablisher.establish(any(), any(), eq(accountId.value()), anyString()))
        .thenReturn("/platform/dashboard");

    mockMvc
        .perform(
            post("/platform/login")
                .param("email", "founder@example.com")
                .param("password", "correct-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/dashboard"));

    verify(useCase)
        .handle(
            new AuthenticatePlatformAccountWithPasswordCommand(
                new Email("founder@example.com"), "correct-password"));
  }

  // TD-FUT-026: proves the new-device notification wiring actually fires on a real successful
  // login, not just that the constructor accepts the extra collaborator.
  @Test
  void validCredentialsAlsoRecordTheLoginDevice() throws Exception {
    PlatformAccountId accountId = PlatformAccountId.newId();
    when(useCase.handle(any())).thenReturn(accountId);
    when(sessionEstablisher.establish(any(), any(), eq(accountId.value()), anyString()))
        .thenReturn("/platform/dashboard");

    mockMvc.perform(
        post("/platform/login")
            .param("email", "founder@example.com")
            .param("password", "correct-password"));

    verify(recordLoginDevice).handle(any());
  }

  @Test
  void invalidEmailRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(post("/platform/login").param("email", "not-an-email").param("password", "x"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/login"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(useCase);
    verifyNoInteractions(sessionEstablisher);
  }

  @Test
  void wrongCredentialsRerenderTheFormWithAGenericErrorNeverAFieldError() throws Exception {
    when(useCase.handle(any())).thenThrow(new InvalidPlatformCredentialsException());

    mockMvc
        .perform(
            post("/platform/login")
                .param("email", "founder@example.com")
                .param("password", "wrong-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/login"))
        .andExpect(model().attribute("loginError", true))
        .andExpect(model().attributeHasNoErrors("form"));

    verify(sessionEstablisher, never()).establish(any(), any(), any(), anyString());
  }
}

package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ActivePlatformAccountSession;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ListActiveSessionsForPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.revokeplatformaccountsession.PlatformAccountSessionNotFoundException;
import com.clavaris.identity.application.usecase.revokeplatformaccountsession.RevokePlatformAccountSessionCommand;
import com.clavaris.identity.application.usecase.revokeplatformaccountsession.RevokePlatformAccountSessionUseCase;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * TD-FUT-026: platform-tier mirror of {@code AccountSessionsControllerTest} — same standalone
 * MockMvc + real Thymeleaf setup rationale as {@code LoginControllerTest}.
 */
class PlatformAccountSessionsControllerTest {

  private ListActiveSessionsForPlatformAccountUseCase listSessions;
  private RevokePlatformAccountSessionUseCase revokeSession;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private PlatformAccountId platformAccountId;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    listSessions = mock(ListActiveSessionsForPlatformAccountUseCase.class);
    revokeSession = mock(RevokePlatformAccountSessionUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);
    platformAccountId = PlatformAccountId.newId();
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(platformAccountId));

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
                new PlatformAccountSessionsController(
                    listSessions, revokeSession, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getRendersEveryLiveSessionForTheCurrentPlatformAccount() throws Exception {
    ActivePlatformAccountSession session =
        new ActivePlatformAccountSession(
            "session-1", "Mozilla/5.0", "1.2.3.4", Instant.now(), Instant.now());
    when(listSessions.handle(any())).thenReturn(List.of(session));

    mockMvc
        .perform(get("/platform/account/sessions"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/account-sessions"))
        .andExpect(model().attribute("sessions", List.of(session)));
  }

  @Test
  void getRendersAFriendlyDeviceLabelNotTheRawUserAgent() throws Exception {
    String chromeOnWindows =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/128.0.0.0 Safari/537.36";
    ActivePlatformAccountSession session =
        new ActivePlatformAccountSession(
            "session-1", chromeOnWindows, "1.2.3.4", Instant.now(), Instant.now());
    when(listSessions.handle(any())).thenReturn(List.of(session));

    mockMvc
        .perform(get("/platform/account/sessions"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Chrome on Windows")))
        .andExpect(
            model().attribute("friendlyDeviceLabels", Map.of("session-1", "Chrome on Windows")));
  }

  @Test
  void postRevokeDelegatesToTheUseCaseWithTheCurrentPlatformAccountAndRedirectsBackToTheList()
      throws Exception {
    mockMvc
        .perform(post("/platform/account/sessions/{sessionId}/revoke", "session-1"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/account/sessions"));

    verify(revokeSession)
        .handle(new RevokePlatformAccountSessionCommand(platformAccountId, "session-1"));
  }

  @Test
  void postRevokeOnAnUnresolvableSessionStillRedirectsBackCleanly() throws Exception {
    doThrow(new PlatformAccountSessionNotFoundException("gone")).when(revokeSession).handle(any());

    mockMvc
        .perform(post("/platform/account/sessions/{sessionId}/revoke", "gone"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/account/sessions"));
  }
}

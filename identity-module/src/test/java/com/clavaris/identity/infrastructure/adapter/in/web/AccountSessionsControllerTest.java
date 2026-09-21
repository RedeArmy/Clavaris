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

import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ActiveAccountSession;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ListActiveSessionsForAccountUseCase;
import com.clavaris.identity.application.usecase.revokeaccountsession.RevokeAccountSessionCommand;
import com.clavaris.identity.application.usecase.revokeaccountsession.RevokeAccountSessionUseCase;
import com.clavaris.identity.application.usecase.revokeaccountsession.SessionNotFoundException;
import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Same standalone MockMvc + real Thymeleaf setup as {@link LoginControllerTest} — see its Javadoc
 * for why.
 */
class AccountSessionsControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private ListActiveSessionsForAccountUseCase listSessions;
  private RevokeAccountSessionUseCase revokeSession;
  private CurrentAccountResolver currentAccount;
  private AccountId accountId;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    listSessions = mock(ListActiveSessionsForAccountUseCase.class);
    revokeSession = mock(RevokeAccountSessionUseCase.class);
    currentAccount = mock(CurrentAccountResolver.class);
    accountId = AccountId.newId();
    when(currentAccount.resolve(any())).thenReturn(Optional.of(accountId));

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
                new AccountSessionsController(listSessions, revokeSession, currentAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getRendersEveryLiveSessionForTheCurrentAccount() throws Exception {
    ActiveAccountSession session =
        new ActiveAccountSession(
            "session-1", "Mozilla/5.0", "1.2.3.4", Instant.now(), Instant.now());
    when(listSessions.handle(any())).thenReturn(List.of(session));

    mockMvc
        .perform(get("/o/{organizationId}/account/sessions", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/sessions"))
        .andExpect(model().attribute("sessions", List.of(session)));
  }

  // SonarCloud "Duplicated Lines" follow-up (2026-09-20): the sessions table itself now renders
  // via a shared fragment (identity/fragments/sessions-table.html, also used by
  // PlatformAccountSessionsController's own equivalent page) that builds each row's revoke form
  // action by string-concatenating this org-scoped base onto a plain-String sessionId — asserting
  // the real rendered URL, not just that the page renders at all, since a wrong base/suffix
  // wouldn't fail to render, only silently 404 on submit.
  @Test
  void revokeFormPostsToTheOrganizationScopedRevokeUrl() throws Exception {
    ActiveAccountSession session =
        new ActiveAccountSession(
            "session-1", "Mozilla/5.0", "1.2.3.4", Instant.now(), Instant.now());
    when(listSessions.handle(any())).thenReturn(List.of(session));

    mockMvc
        .perform(get("/o/{organizationId}/account/sessions", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "action=\"/o/"
                            + ORGANIZATION_ID
                            + "/account/sessions/session-1/revoke\"")));
  }

  // TD-FUT-024: proves the friendly label actually reaches the rendered page, not just that
  // UserAgentLabel's own unit tests pass in isolation — a real Thymeleaf render (see this class's
  // own Javadoc), not a mocked view.
  @Test
  void getRendersAFriendlyDeviceLabelNotTheRawUserAgent() throws Exception {
    String chromeOnWindows =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/128.0.0.0 Safari/537.36";
    ActiveAccountSession session =
        new ActiveAccountSession(
            "session-1", chromeOnWindows, "1.2.3.4", Instant.now(), Instant.now());
    when(listSessions.handle(any())).thenReturn(List.of(session));

    mockMvc
        .perform(get("/o/{organizationId}/account/sessions", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Chrome on Windows")))
        .andExpect(
            model().attribute("friendlyDeviceLabels", Map.of("session-1", "Chrome on Windows")));
  }

  @Test
  void postRevokeDelegatesToTheUseCaseWithTheCurrentAccountAndRedirectsBackToTheList()
      throws Exception {
    mockMvc
        .perform(
            post(
                "/o/{organizationId}/account/sessions/{sessionId}/revoke",
                ORGANIZATION_ID,
                "session-1"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/account/sessions"));

    verify(revokeSession).handle(new RevokeAccountSessionCommand(accountId, "session-1"));
  }

  @Test
  void postRevokeOnAnUnresolvableSessionStillRedirectsBackCleanly() throws Exception {
    doThrow(new SessionNotFoundException("gone")).when(revokeSession).handle(any());

    mockMvc
        .perform(
            post(
                "/o/{organizationId}/account/sessions/{sessionId}/revoke", ORGANIZATION_ID, "gone"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/account/sessions"));
  }
}

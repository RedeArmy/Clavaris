package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.webhook.application.usecase.deletewebhookendpoint.DeleteWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.getwebhookendpointfororganization.GetWebhookEndpointForOrganizationUseCase;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Same standalone MockMvc + real Thymeleaf setup, and same real-{@code HttpSession}-carries-
 * across-requests shape, as client-registry-module's own {@code
 * PlatformDeleteOAuthClientControllerTest}.
 */
class PlatformDeleteWebhookEndpointControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final String CONFIRM_VIEW = "webhook/platform/delete-webhook-endpoint-confirm";

  private GetWebhookEndpointForOrganizationUseCase getEndpoint;
  private DeleteWebhookEndpointUseCase deleteEndpoint;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    getEndpoint = mock(GetWebhookEndpointForOrganizationUseCase.class);
    deleteEndpoint = mock(DeleteWebhookEndpointUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));

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
                new PlatformDeleteWebhookEndpointController(
                    getEndpoint, deleteEndpoint, organizationResolver, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private WebhookEndpoint activeEndpoint() {
    return WebhookEndpoint.register(
        organizationId, "https://example.com/webhooks", null, List.of("x"), "secret");
  }

  private WebhookEndpoint inactiveEndpoint() {
    return activeEndpoint().deactivate();
  }

  private String basePath(final UUID endpointId) {
    return "/platform/dashboard/organizations/"
        + organizationId
        + "/webhook-endpoints/"
        + endpointId
        + "/delete";
  }

  @Test
  void showsTheConfirmationPageWithAFreshTokenForAnInactiveEndpoint() throws Exception {
    WebhookEndpoint endpoint = inactiveEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));

    mockMvc
        .perform(get(basePath(endpoint.id())))
        .andExpect(status().isOk())
        .andExpect(view().name(CONFIRM_VIEW))
        .andExpect(model().attribute("endpoint", endpoint))
        .andExpect(model().attributeExists("confirmationToken"));
  }

  // Live UX request, 2026-09-25: permanent deletion only ever reachable once the endpoint is
  // already inactive — the link to this page is itself hidden while active, and a direct GET
  // redirects back to the detail page instead of rendering the confirm form.
  @Test
  void redirectsBackToTheDetailPageForAnActiveEndpoint() throws Exception {
    WebhookEndpoint endpoint = activeEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));

    mockMvc
        .perform(get(basePath(endpoint.id())))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl(
                "/platform/dashboard/organizations/"
                    + organizationId
                    + "/webhook-endpoints/"
                    + endpoint.id()));

    verify(deleteEndpoint, never()).handle(any());
  }

  @Test
  void returnsNotFoundWhenTheEndpointBelongsToADifferentOrganization() throws Exception {
    when(getEndpoint.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath(UUID.randomUUID()))).andExpect(status().isNotFound());
  }

  @Test
  void aValidTokenAndTheLiteralWordDeleteDeletesAndRedirectsToTheList() throws Exception {
    WebhookEndpoint endpoint = inactiveEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));
    MvcResult confirmPage = mockMvc.perform(get(basePath(endpoint.id()))).andReturn();
    String token = (String) confirmPage.getModelAndView().getModel().get("confirmationToken");

    mockMvc
        .perform(
            post(basePath(endpoint.id()))
                .session((MockHttpSession) confirmPage.getRequest().getSession())
                .param("confirmationToken", token)
                .param("confirmedText", "delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl(
                "/platform/dashboard/organizations/" + organizationId + "/webhook-endpoints"));

    verify(deleteEndpoint).handle(any());
  }

  @Test
  void aTokenCannotBeReusedForASecondDeleteAttempt() throws Exception {
    WebhookEndpoint endpoint = inactiveEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));
    MvcResult confirmPage = mockMvc.perform(get(basePath(endpoint.id()))).andReturn();
    String token = (String) confirmPage.getModelAndView().getModel().get("confirmationToken");
    MockHttpSession session = (MockHttpSession) confirmPage.getRequest().getSession();

    mockMvc.perform(
        post(basePath(endpoint.id()))
            .session(session)
            .param("confirmationToken", token)
            .param("confirmedText", "delete"));

    mockMvc
        .perform(
            post(basePath(endpoint.id()))
                .session(session)
                .param("confirmationToken", token)
                .param("confirmedText", "delete"))
        .andExpect(status().isOk())
        .andExpect(view().name(CONFIRM_VIEW))
        .andExpect(model().attribute("confirmationExpiredError", true));

    verify(deleteEndpoint).handle(any());
  }

  @Test
  void aWrongTokenRendersAnInlineErrorWithoutDeletingAnything() throws Exception {
    WebhookEndpoint endpoint = inactiveEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));
    MvcResult confirmPage = mockMvc.perform(get(basePath(endpoint.id()))).andReturn();
    MockHttpSession session = (MockHttpSession) confirmPage.getRequest().getSession();

    mockMvc
        .perform(
            post(basePath(endpoint.id()))
                .session(session)
                .param("confirmationToken", "not-the-real-token")
                .param("confirmedText", "delete"))
        .andExpect(status().isOk())
        .andExpect(view().name(CONFIRM_VIEW))
        .andExpect(model().attribute("confirmationExpiredError", true));

    verify(deleteEndpoint, never()).handle(any());
  }

  @Test
  void aMismatchedConfirmationRendersAnInlineErrorWithoutDeletingAnything() throws Exception {
    WebhookEndpoint endpoint = inactiveEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));
    MvcResult confirmPage = mockMvc.perform(get(basePath(endpoint.id()))).andReturn();
    String token = (String) confirmPage.getModelAndView().getModel().get("confirmationToken");
    MockHttpSession session = (MockHttpSession) confirmPage.getRequest().getSession();

    mockMvc
        .perform(
            post(basePath(endpoint.id()))
                .session(session)
                .param("confirmationToken", token)
                .param("confirmedText", "not the right thing"))
        .andExpect(status().isOk())
        .andExpect(view().name(CONFIRM_VIEW))
        .andExpect(model().attribute("confirmationMismatchError", true));

    verify(deleteEndpoint, never()).handle(any());
  }
}

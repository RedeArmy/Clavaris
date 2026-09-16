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

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.webhook.application.usecase.activatewebhookendpoint.ActivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.deactivatewebhookendpoint.DeactivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganizationpaged.ListWebhookEndpointsForOrganizationPagedQuery;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganizationpaged.ListWebhookEndpointsForOrganizationPagedUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointResult;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.UnsafeWebhookUrlException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointLimitExceededException;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretResult;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretUseCase;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
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
 * Same standalone MockMvc + real Thymeleaf setup as client-registry-module's own {@code
 * PlatformOAuthClientControllerTest}.
 */
class PlatformWebhookEndpointControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private RegisterWebhookEndpointUseCase registerEndpoint;
  private ListWebhookEndpointsForOrganizationUseCase listEndpoints;
  private ListWebhookEndpointsForOrganizationPagedUseCase listEndpointsPaged;
  private DeactivateWebhookEndpointUseCase deactivateEndpoint;
  private ActivateWebhookEndpointUseCase activateEndpoint;
  private RotateWebhookEndpointSecretUseCase rotateEndpointSecret;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    registerEndpoint = mock(RegisterWebhookEndpointUseCase.class);
    listEndpoints = mock(ListWebhookEndpointsForOrganizationUseCase.class);
    listEndpointsPaged = mock(ListWebhookEndpointsForOrganizationPagedUseCase.class);
    deactivateEndpoint = mock(DeactivateWebhookEndpointUseCase.class);
    activateEndpoint = mock(ActivateWebhookEndpointUseCase.class);
    rotateEndpointSecret = mock(RotateWebhookEndpointSecretUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(listEndpoints.handle(any())).thenReturn(List.of());
    when(listEndpointsPaged.handle(any())).thenReturn(emptyPage());

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
                new PlatformWebhookEndpointController(
                    registerEndpoint,
                    listEndpoints,
                    listEndpointsPaged,
                    deactivateEndpoint,
                    activateEndpoint,
                    rotateEndpointSecret,
                    organizationResolver,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String basePath() {
    return "/platform/dashboard/organizations/" + organizationId + "/webhook-endpoints";
  }

  private WebhookEndpoint sampleEndpoint() {
    return WebhookEndpoint.register(
        organizationId,
        "https://example.com/webhooks/clavaris",
        "Production",
        List.of(KnownWebhookEventTypeOptions.DASHBOARD_OPTIONS.get(0)),
        "encrypted-secret");
  }

  private static KeysetPage<WebhookEndpoint> emptyPage() {
    return new KeysetPage<>(List.of(), null, null, false, false);
  }

  private static KeysetCursor cursorOf(final WebhookEndpoint endpoint) {
    return new KeysetCursor(endpoint.createdAt(), endpoint.id());
  }

  @Test
  void showsTheOrganizationsWebhookEndpoints() throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    KeysetCursor cursor = cursorOf(endpoint);
    when(listEndpointsPaged.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(endpoint), cursor, cursor, false, false));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/organization-webhook-endpoints"))
        .andExpect(model().attribute("organizationName", "Acme Co"))
        .andExpect(model().attribute("endpoints", List.of(endpoint)));
  }

  // TD-PERF-020 (keyset revision): proves ?after= is actually decoded and threaded into the
  // query.
  @Test
  void getPassesTheAfterCursorThroughToTheUseCase() throws Exception {
    KeysetCursor cursor = new KeysetCursor(java.time.Instant.now(), UUID.randomUUID());

    mockMvc.perform(get(basePath()).param("after", cursor.encode()));

    verify(listEndpointsPaged)
        .handle(
            new ListWebhookEndpointsForOrganizationPagedQuery(
                organizationId, KeysetPageRequest.after(cursor)));
  }

  // TD-PERF-020: an HTMX-originated pagination link (hx-get) must get back just the endpoints
  // fragment, not the full page.
  @Test
  void htmxGetReturnsTheEndpointsFragmentInsteadOfTheFullPage() throws Exception {
    mockMvc
        .perform(get(basePath()).header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/organization-webhook-endpoints :: endpoints"));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath())).andExpect(status().isNotFound());
  }

  @Test
  void plainCreatePostRendersThePageDirectlyWithTheOneTimeSecretNeverARedirect() throws Exception {
    WebhookEndpoint created = sampleEndpoint();
    when(registerEndpoint.handle(any()))
        .thenReturn(new RegisterWebhookEndpointResult(created, "raw-secret-shown-once"));

    mockMvc
        .perform(
            post(basePath())
                .param("url", "https://example.com/webhooks/clavaris")
                .param(
                    "subscribedEventTypes", KnownWebhookEventTypeOptions.DASHBOARD_OPTIONS.get(0)))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/organization-webhook-endpoints"))
        .andExpect(model().attribute("justRegisteredRawSecret", "raw-secret-shown-once"));

    verify(registerEndpoint).handle(any());
  }

  @Test
  void htmxCreatePostReturnsTheEndpointsFragment() throws Exception {
    WebhookEndpoint created = sampleEndpoint();
    when(registerEndpoint.handle(any()))
        .thenReturn(new RegisterWebhookEndpointResult(created, "raw-secret-shown-once"));

    mockMvc
        .perform(
            post(basePath())
                .param("url", "https://example.com/webhooks/clavaris")
                .param(
                    "subscribedEventTypes", KnownWebhookEventTypeOptions.DASHBOARD_OPTIONS.get(0))
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/organization-webhook-endpoints :: endpoints"));
  }

  @Test
  void createWithNoUrlRendersAnErrorWithoutRegisteringAnything() throws Exception {
    mockMvc
        .perform(
            post(basePath())
                .param(
                    "subscribedEventTypes", KnownWebhookEventTypeOptions.DASHBOARD_OPTIONS.get(0)))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/organization-webhook-endpoints"));

    verify(registerEndpoint, never()).handle(any());
  }

  @Test
  void createWithNoEventTypesSelectedRendersAnErrorWithoutRegisteringAnything() throws Exception {
    mockMvc
        .perform(post(basePath()).param("url", "https://example.com/webhooks/clavaris"))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/organization-webhook-endpoints"));

    verify(registerEndpoint, never()).handle(any());
  }

  // TD-SEC-053: the real, load-bearing behavior — a URL the SSRF guard rejects surfaces as a form
  // error, not a raw 500/400 with no explanation.
  @Test
  void createWithAnUnsafeUrlRendersAnErrorWithoutRegisteringAnything() throws Exception {
    when(registerEndpoint.handle(any()))
        .thenThrow(new UnsafeWebhookUrlException("private address"));

    mockMvc
        .perform(
            post(basePath())
                .param("url", "http://169.254.169.254/latest/meta-data")
                .param(
                    "subscribedEventTypes", KnownWebhookEventTypeOptions.DASHBOARD_OPTIONS.get(0)))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/organization-webhook-endpoints"))
        .andExpect(model().attribute("unsafeWebhookUrlError", true));
  }

  // BR-WEBHOOK-08 (SDE-III review, 2026-09-15) — same "form error, not a bare status code"
  // reasoning as createWithAnUnsafeUrlRendersAnErrorWithoutRegisteringAnything above.
  @Test
  void createAtTheEndpointCapRendersAnErrorWithoutRegisteringAnything() throws Exception {
    when(registerEndpoint.handle(any()))
        .thenThrow(new WebhookEndpointLimitExceededException(organizationId, 25));

    mockMvc
        .perform(
            post(basePath())
                .param("url", "https://example.com/webhooks/clavaris")
                .param(
                    "subscribedEventTypes", KnownWebhookEventTypeOptions.DASHBOARD_OPTIONS.get(0)))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/organization-webhook-endpoints"))
        .andExpect(model().attribute("webhookEndpointLimitExceededError", true));
  }

  @Test
  void plainDeactivatePostRedirectsOnSuccess() throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(listEndpoints.handle(any())).thenReturn(List.of(endpoint));

    mockMvc
        .perform(post(basePath() + "/" + endpoint.id() + "/deactivate"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath()));

    verify(deactivateEndpoint).handle(any());
  }

  @Test
  void deactivateReturnsNotFoundWhenTheEndpointBelongsToADifferentOrganization() throws Exception {
    when(listEndpoints.handle(any())).thenReturn(List.of());

    mockMvc
        .perform(post(basePath() + "/" + UUID.randomUUID() + "/deactivate"))
        .andExpect(status().isNotFound());

    verify(deactivateEndpoint, never()).handle(any());
  }

  @Test
  void plainActivatePostRedirectsOnSuccess() throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(listEndpoints.handle(any())).thenReturn(List.of(endpoint));

    mockMvc
        .perform(post(basePath() + "/" + endpoint.id() + "/activate"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath()));

    verify(activateEndpoint).handle(any());
  }

  @Test
  void activateReturnsNotFoundWhenTheEndpointBelongsToADifferentOrganization() throws Exception {
    when(listEndpoints.handle(any())).thenReturn(List.of());

    mockMvc
        .perform(post(basePath() + "/" + UUID.randomUUID() + "/activate"))
        .andExpect(status().isNotFound());

    verify(activateEndpoint, never()).handle(any());
  }

  @Test
  void plainRotateSecretPostRendersThePageDirectlyWithTheNewSecretNeverARedirect()
      throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(listEndpoints.handle(any())).thenReturn(List.of(endpoint));
    when(rotateEndpointSecret.handle(any()))
        .thenReturn(new RotateWebhookEndpointSecretResult(endpoint, "new-raw-secret"));

    mockMvc
        .perform(post(basePath() + "/" + endpoint.id() + "/rotate-secret"))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/organization-webhook-endpoints"))
        .andExpect(model().attribute("justRegisteredRawSecret", "new-raw-secret"));
  }

  @Test
  void rotateSecretReturnsNotFoundWhenTheEndpointBelongsToADifferentOrganization()
      throws Exception {
    when(listEndpoints.handle(any())).thenReturn(List.of());

    mockMvc
        .perform(post(basePath() + "/" + UUID.randomUUID() + "/rotate-secret"))
        .andExpect(status().isNotFound());

    verify(rotateEndpointSecret, never()).handle(any());
  }
}

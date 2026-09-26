package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.webhook.application.usecase.activatewebhookendpoint.ActivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.deactivatewebhookendpoint.DeactivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.getwebhookendpointfororganization.GetWebhookEndpointForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganizationpaged.ListWebhookEndpointsForOrganizationPagedQuery;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganizationpaged.ListWebhookEndpointsForOrganizationPagedUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointResult;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.UnsafeWebhookUrlException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointLimitExceededException;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretResult;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretUseCase;
import com.clavaris.webhook.application.usecase.updatewebhookendpointdescription.UpdateWebhookEndpointDescriptionUseCase;
import com.clavaris.webhook.application.usecase.updatewebhookendpointeventtypes.UpdateWebhookEndpointEventTypesUseCase;
import com.clavaris.webhook.application.usecase.updatewebhookendpointurl.UpdateWebhookEndpointUrlUseCase;
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
 * PlatformOAuthClientControllerTest}. Rewritten alongside the controller's own live UX request,
 * 2026-09-25 (Clerk-parity master-detail redesign) — registration moved to its own page, and every
 * per-endpoint action now lives on a new detail page instead of the list's own row actions.
 */
class PlatformWebhookEndpointControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final String LIST_VIEW = "webhook/platform/organization-webhook-endpoints";
  private static final String REGISTER_VIEW = "webhook/platform/register-webhook-endpoint";
  private static final String DETAIL_VIEW = "webhook/platform/organization-webhook-endpoint-detail";

  private RegisterWebhookEndpointUseCase registerEndpoint;
  private GetWebhookEndpointForOrganizationUseCase getEndpoint;
  private ListWebhookEndpointsForOrganizationPagedUseCase listEndpointsPaged;
  private DeactivateWebhookEndpointUseCase deactivateEndpoint;
  private ActivateWebhookEndpointUseCase activateEndpoint;
  private RotateWebhookEndpointSecretUseCase rotateEndpointSecret;
  private UpdateWebhookEndpointUrlUseCase updateEndpointUrl;
  private UpdateWebhookEndpointDescriptionUseCase updateEndpointDescription;
  private UpdateWebhookEndpointEventTypesUseCase updateEndpointEventTypes;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    registerEndpoint = mock(RegisterWebhookEndpointUseCase.class);
    getEndpoint = mock(GetWebhookEndpointForOrganizationUseCase.class);
    listEndpointsPaged = mock(ListWebhookEndpointsForOrganizationPagedUseCase.class);
    deactivateEndpoint = mock(DeactivateWebhookEndpointUseCase.class);
    activateEndpoint = mock(ActivateWebhookEndpointUseCase.class);
    rotateEndpointSecret = mock(RotateWebhookEndpointSecretUseCase.class);
    updateEndpointUrl = mock(UpdateWebhookEndpointUrlUseCase.class);
    updateEndpointDescription = mock(UpdateWebhookEndpointDescriptionUseCase.class);
    updateEndpointEventTypes = mock(UpdateWebhookEndpointEventTypesUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(getEndpoint.handle(any())).thenReturn(Optional.empty());
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
                    getEndpoint,
                    listEndpointsPaged,
                    deactivateEndpoint,
                    activateEndpoint,
                    rotateEndpointSecret,
                    updateEndpointUrl,
                    updateEndpointDescription,
                    updateEndpointEventTypes,
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

  // Live bug, 2026-09-22: see PlatformOAuthClientControllerTest's own identical test for the full
  // rationale — every script here loads unconditionally from dashboard-nav.html.
  //
  // event-type-picker.js added 2026-09-26 after a real live bug of the identical class: the
  // picker's own parent/child checkboxes did nothing in a real browser (CSP silently blocked the
  // inline onchange="..."/<script> this fragment used to carry) — this content assertion, like
  // the two scripts above it, only proves the <script src> tag is present in the response, never
  // that the JS inside actually runs or that CSP allows it; that gap is exactly how the original
  // bug shipped undetected. See event-type-picker.js's own comment for the fix.
  @Test
  void sidebarLoadsEveryScriptManageAccountAndTheEventPickerNeed() throws Exception {
    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/js/htmx.min.js")))
        .andExpect(content().string(containsString("/js/organization-dialog.js")))
        .andExpect(content().string(containsString("/js/event-type-picker.js")));
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
        .andExpect(view().name(LIST_VIEW))
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
        .andExpect(view().name(LIST_VIEW + " :: endpoints"));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath())).andExpect(status().isNotFound());
  }

  // Live UX request, 2026-09-25: registration moved to its own page.
  @Test
  void showRegisterFormRendersTheRegisterViewWithAFreshForm() throws Exception {
    mockMvc
        .perform(get(basePath() + "/new"))
        .andExpect(status().isOk())
        .andExpect(view().name(REGISTER_VIEW))
        .andExpect(model().attribute("organizationName", "Acme Co"));
  }

  @Test
  void showRegisterFormReturnsNotFoundWhenTheOrganizationIsNotOwned() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath() + "/new")).andExpect(status().isNotFound());
  }

  // Live UX request, 2026-09-25: a successful registration now renders the new endpoint's own
  // detail page directly (never a redirect — the raw secret can't safely travel through one).
  @Test
  void plainCreatePostRendersTheDetailPageDirectlyWithTheOneTimeSecretNeverARedirect()
      throws Exception {
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
        .andExpect(view().name(DETAIL_VIEW))
        .andExpect(model().attribute("endpoint", created))
        .andExpect(model().attribute("justRegisteredRawSecret", "raw-secret-shown-once"));

    verify(registerEndpoint).handle(any());
  }

  @Test
  void createWithNoUrlRendersAnErrorOnTheRegisterViewWithoutRegisteringAnything() throws Exception {
    mockMvc
        .perform(
            post(basePath())
                .param(
                    "subscribedEventTypes", KnownWebhookEventTypeOptions.DASHBOARD_OPTIONS.get(0)))
        .andExpect(status().isOk())
        .andExpect(view().name(REGISTER_VIEW));

    verify(registerEndpoint, never()).handle(any());
  }

  @Test
  void createWithNoEventTypesSelectedRendersAnErrorOnTheRegisterViewWithoutRegisteringAnything()
      throws Exception {
    mockMvc
        .perform(post(basePath()).param("url", "https://example.com/webhooks/clavaris"))
        .andExpect(status().isOk())
        .andExpect(view().name(REGISTER_VIEW));

    verify(registerEndpoint, never()).handle(any());
  }

  // TD-SEC-053: the real, load-bearing behavior — a URL the SSRF guard rejects surfaces as a form
  // error, not a raw 500/400 with no explanation.
  @Test
  void createWithAnUnsafeUrlRendersAnErrorOnTheRegisterViewWithoutRegisteringAnything()
      throws Exception {
    when(registerEndpoint.handle(any()))
        .thenThrow(new UnsafeWebhookUrlException("private address"));

    mockMvc
        .perform(
            post(basePath())
                .param("url", "http://169.254.169.254/latest/meta-data")
                .param(
                    "subscribedEventTypes", KnownWebhookEventTypeOptions.DASHBOARD_OPTIONS.get(0)))
        .andExpect(status().isOk())
        .andExpect(view().name(REGISTER_VIEW))
        .andExpect(model().attribute("unsafeWebhookUrlError", true));
  }

  // BR-WEBHOOK-08 (SDE-III review, 2026-09-15) — same "form error, not a bare status code"
  // reasoning as createWithAnUnsafeUrlRendersAnErrorOnTheRegisterViewWithoutRegisteringAnything.
  @Test
  void createAtTheEndpointCapRendersAnErrorOnTheRegisterViewWithoutRegisteringAnything()
      throws Exception {
    when(registerEndpoint.handle(any()))
        .thenThrow(new WebhookEndpointLimitExceededException(organizationId, 25));

    mockMvc
        .perform(
            post(basePath())
                .param("url", "https://example.com/webhooks/clavaris")
                .param(
                    "subscribedEventTypes", KnownWebhookEventTypeOptions.DASHBOARD_OPTIONS.get(0)))
        .andExpect(status().isOk())
        .andExpect(view().name(REGISTER_VIEW))
        .andExpect(model().attribute("webhookEndpointLimitExceededError", true));
  }

  // Live UX request, 2026-09-25: showDetail renders the new per-endpoint detail page.
  @Test
  void showDetailRendersTheDetailViewForAnOwnedEndpoint() throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));

    mockMvc
        .perform(get(basePath() + "/" + endpoint.id()))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW))
        .andExpect(model().attribute("endpoint", endpoint));
  }

  @Test
  void showDetailReturnsNotFoundWhenTheEndpointBelongsToADifferentOrganization() throws Exception {
    when(getEndpoint.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath() + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  // Live UX request, 2026-09-25: deactivate/activate/rotate-secret now redirect to (or render) the
  // endpoint's own detail page — its per-row actions moved off the list.
  @Test
  void plainDeactivatePostRedirectsToTheDetailPageOnSuccess() throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));

    mockMvc
        .perform(post(basePath() + "/" + endpoint.id() + "/deactivate"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath() + "/" + endpoint.id()));

    verify(deactivateEndpoint).handle(any());
  }

  @Test
  void deactivateReturnsNotFoundWhenTheEndpointBelongsToADifferentOrganization() throws Exception {
    when(getEndpoint.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(post(basePath() + "/" + UUID.randomUUID() + "/deactivate"))
        .andExpect(status().isNotFound());

    verify(deactivateEndpoint, never()).handle(any());
  }

  @Test
  void plainActivatePostRedirectsToTheDetailPageOnSuccess() throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));

    mockMvc
        .perform(post(basePath() + "/" + endpoint.id() + "/activate"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath() + "/" + endpoint.id()));

    verify(activateEndpoint).handle(any());
  }

  @Test
  void activateReturnsNotFoundWhenTheEndpointBelongsToADifferentOrganization() throws Exception {
    when(getEndpoint.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(post(basePath() + "/" + UUID.randomUUID() + "/activate"))
        .andExpect(status().isNotFound());

    verify(activateEndpoint, never()).handle(any());
  }

  @Test
  void plainRotateSecretPostRendersTheDetailPageDirectlyWithTheNewSecretNeverARedirect()
      throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));
    when(rotateEndpointSecret.handle(any()))
        .thenReturn(new RotateWebhookEndpointSecretResult(endpoint, "new-raw-secret"));

    mockMvc
        .perform(post(basePath() + "/" + endpoint.id() + "/rotate-secret"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW))
        .andExpect(model().attribute("justRegisteredRawSecret", "new-raw-secret"));
  }

  @Test
  void rotateSecretReturnsNotFoundWhenTheEndpointBelongsToADifferentOrganization()
      throws Exception {
    when(getEndpoint.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(post(basePath() + "/" + UUID.randomUUID() + "/rotate-secret"))
        .andExpect(status().isNotFound());

    verify(rotateEndpointSecret, never()).handle(any());
  }

  // Live UX request, 2026-09-25: URL/description/event types editable after registration.
  @Test
  void plainUpdateUrlPostRedirectsToTheDetailPageOnSuccess() throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));
    when(updateEndpointUrl.handle(any())).thenReturn(endpoint.updateUrl("https://new.example.com"));

    mockMvc
        .perform(
            post(basePath() + "/" + endpoint.id() + "/url").param("url", "https://new.example.com"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath() + "/" + endpoint.id()));

    verify(updateEndpointUrl).handle(any());
  }

  @Test
  void updateUrlWithAnUnsafeUrlRendersTheDetailPageWithAnErrorWithoutUpdating() throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));
    when(updateEndpointUrl.handle(any()))
        .thenThrow(new UnsafeWebhookUrlException("private address"));

    mockMvc
        .perform(
            post(basePath() + "/" + endpoint.id() + "/url")
                .param("url", "http://169.254.169.254/latest/meta-data"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW))
        .andExpect(model().attribute("unsafeWebhookUrlError", true));
  }

  @Test
  void updateUrlReturnsNotFoundWhenTheEndpointBelongsToADifferentOrganization() throws Exception {
    when(getEndpoint.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post(basePath() + "/" + UUID.randomUUID() + "/url").param("url", "https://example.com"))
        .andExpect(status().isNotFound());

    verify(updateEndpointUrl, never()).handle(any());
  }

  @Test
  void plainUpdateDescriptionPostRedirectsToTheDetailPageOnSuccess() throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));

    mockMvc
        .perform(
            post(basePath() + "/" + endpoint.id() + "/description")
                .param("description", "new description"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath() + "/" + endpoint.id()));

    verify(updateEndpointDescription).handle(any());
  }

  @Test
  void updateDescriptionReturnsNotFoundWhenTheEndpointBelongsToADifferentOrganization()
      throws Exception {
    when(getEndpoint.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(post(basePath() + "/" + UUID.randomUUID() + "/description"))
        .andExpect(status().isNotFound());

    verify(updateEndpointDescription, never()).handle(any());
  }

  @Test
  void plainUpdateEventTypesPostRedirectsToTheDetailPageOnSuccess() throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));

    mockMvc
        .perform(
            post(basePath() + "/" + endpoint.id() + "/event-types")
                .param("subscribedEventTypes", "account.deleted"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath() + "/" + endpoint.id()));

    verify(updateEndpointEventTypes).handle(any());
  }

  // Live UX request, 2026-09-25 (PayPal-parity "All Events"): the wildcard is a genuinely valid
  // single-entry submission, not an "empty selection" the controller should reject.
  @Test
  void updateEventTypesAcceptsTheAllEventsWildcard() throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));

    mockMvc
        .perform(
            post(basePath() + "/" + endpoint.id() + "/event-types")
                .param("subscribedEventTypes", WebhookEndpoint.ALL_EVENTS_WILDCARD))
        .andExpect(status().is3xxRedirection());

    verify(updateEndpointEventTypes).handle(any());
  }

  @Test
  void updateEventTypesWithNoneSelectedRendersTheDetailPageWithAnErrorWithoutUpdating()
      throws Exception {
    WebhookEndpoint endpoint = sampleEndpoint();
    when(getEndpoint.handle(any())).thenReturn(Optional.of(endpoint));

    mockMvc
        .perform(post(basePath() + "/" + endpoint.id() + "/event-types"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW))
        .andExpect(model().attribute("emptyEventTypesError", true));

    verify(updateEndpointEventTypes, never()).handle(any());
  }

  @Test
  void updateEventTypesReturnsNotFoundWhenTheEndpointBelongsToADifferentOrganization()
      throws Exception {
    when(getEndpoint.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post(basePath() + "/" + UUID.randomUUID() + "/event-types")
                .param("subscribedEventTypes", "account.created"))
        .andExpect(status().isNotFound());

    verify(updateEndpointEventTypes, never()).handle(any());
  }
}

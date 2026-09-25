package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.webhook.application.usecase.listwebhookdeliveriesfororganizationpaged.ListWebhookDeliveriesForOrganizationPagedUseCase;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.ReplayWebhookDeliveryUseCase;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.WebhookDeliveryNotFoundException;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.WebhookDeliveryNotReplayableException;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.time.Instant;
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

class PlatformWebhookLogsControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private ListWebhookDeliveriesForOrganizationPagedUseCase listDeliveriesPaged;
  private ReplayWebhookDeliveryUseCase replayDelivery;
  private ListWebhookEndpointsForOrganizationUseCase listEndpoints;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;
  private WebhookEndpoint endpoint;

  @BeforeEach
  void setUp() {
    listDeliveriesPaged = mock(ListWebhookDeliveriesForOrganizationPagedUseCase.class);
    replayDelivery = mock(ReplayWebhookDeliveryUseCase.class);
    listEndpoints = mock(ListWebhookEndpointsForOrganizationUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();
    endpoint =
        WebhookEndpoint.register(
            organizationId,
            "https://example.com/webhooks/clavaris",
            "Production",
            List.of(KnownWebhookEventTypeOptions.DASHBOARD_OPTIONS.get(0)),
            "encrypted-secret");

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(listDeliveriesPaged.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(), null, null, false, false));
    when(listEndpoints.handle(any())).thenReturn(List.of(endpoint));

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
                new PlatformWebhookLogsController(
                    listDeliveriesPaged,
                    replayDelivery,
                    listEndpoints,
                    organizationResolver,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String basePath() {
    return "/platform/dashboard/organizations/" + organizationId + "/webhook-endpoints/logs";
  }

  private WebhookDelivery sampleDelivery(final WebhookDeliveryStatus status) {
    WebhookDelivery scheduled =
        WebhookDelivery.schedule(
            endpoint.id(),
            organizationId,
            UUID.randomUUID(),
            "Organization",
            organizationId,
            KnownWebhookEventTypeOptions.DASHBOARD_OPTIONS.get(0),
            "{}",
            null);
    return switch (status) {
      case SUCCEEDED -> scheduled.recordSuccess(200, Instant.now());
      case EXHAUSTED -> scheduled.recordFailure(500, "boom", Instant.now(), null);
      case FAILED ->
          scheduled.recordFailure(500, "boom", Instant.now(), Instant.now().plusSeconds(60));
      case PENDING -> scheduled;
    };
  }

  @Test
  void showsDeliveriesAcrossEveryEndpointWithTheirOwnUrl() throws Exception {
    WebhookDelivery delivery = sampleDelivery(WebhookDeliveryStatus.SUCCEEDED);
    when(listDeliveriesPaged.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(delivery), null, null, false, false));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/webhook-logs"))
        .andExpect(model().attribute("organizationName", "Acme Co"))
        .andExpect(model().attribute("deliveries", List.of(delivery)));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath())).andExpect(status().isNotFound());
  }

  @Test
  void htmxGetReturnsTheLogsFragment() throws Exception {
    mockMvc
        .perform(get(basePath()).header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/webhook-logs :: logs"));
  }

  @Test
  void plainReplayPostRedirectsOnSuccess() throws Exception {
    WebhookDelivery delivery = sampleDelivery(WebhookDeliveryStatus.SUCCEEDED);

    mockMvc
        .perform(post(basePath() + "/" + endpoint.id() + "/" + delivery.id() + "/replay"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath()));

    verify(replayDelivery).handle(any());
  }

  @Test
  void replayReturnsNotFoundWhenTheDeliveryDoesNotExist() throws Exception {
    when(replayDelivery.handle(any()))
        .thenThrow(new WebhookDeliveryNotFoundException(UUID.randomUUID()));

    mockMvc
        .perform(post(basePath() + "/" + endpoint.id() + "/" + UUID.randomUUID() + "/replay"))
        .andExpect(status().isNotFound());
  }

  @Test
  void replayWithANotReplayableDeliveryRendersAnInlineErrorWithoutFailing() throws Exception {
    when(replayDelivery.handle(any()))
        .thenThrow(
            new WebhookDeliveryNotReplayableException(
                UUID.randomUUID(), WebhookDeliveryStatus.PENDING));

    mockMvc
        .perform(post(basePath() + "/" + endpoint.id() + "/" + UUID.randomUUID() + "/replay"))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/webhook-logs"))
        .andExpect(model().attribute("notReplayableError", true));
  }
}

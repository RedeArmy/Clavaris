package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.webhook.application.usecase.activatewebhookendpoint.ActivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.deactivatewebhookendpoint.DeactivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.getwebhookdeliveryactivityfororganization.GetWebhookDeliveryActivityForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.getwebhookdeliveryactivityfororganization.WebhookDeliveryActivity;
import com.clavaris.webhook.application.usecase.getwebhookendpointfororganization.GetWebhookEndpointForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.listwebhookdeliveriesfororganizationpaged.ListWebhookDeliveriesForOrganizationPagedUseCase;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganizationpaged.ListWebhookEndpointsForOrganizationPagedUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.ReplayWebhookDeliveryUseCase;
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
 * Live UX request, 2026-09-25: a real, honest test of the one thing every other webhook dashboard
 * controller test can't check on its own — each registers only its own controller in its own
 * standalone MockMvc, so none of them can prove {@code PlatformWebhookEndpointController}'s own
 * {@code GET /{endpointId}} doesn't shadow the four new literal-path siblings ({@code /new}, {@code
 * /logs}, {@code /activity}, {@code /event-catalog}) once every controller shares one real {@code
 * DispatcherServlet}, the way they do in production. Registers all five together and asserts each
 * literal path reaches its own controller's own view, never {@code
 * PlatformWebhookEndpointController}'s {@code showDetail} (which would either 400 on
 * "logs"/"new"/etc. failing UUID conversion, or — worse — silently 200 with the wrong page).
 */
class WebhookDashboardRoutingTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  @SuppressWarnings("PMD.ExcessiveMethodLength") // wiring five real controllers together, not
  // business logic — same "wiring, not sprawl" reasoning this module's own use-case config
  // classes already document for an identical shape of long-but-flat method.
  void setUp() {
    organizationId = UUID.randomUUID();

    OrganizationForPlatformAccountResolver organizationResolver =
        mock(OrganizationForPlatformAccountResolver.class);
    CurrentPlatformAccountResolver currentPlatformAccount =
        mock(CurrentPlatformAccountResolver.class);
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));

    GetWebhookEndpointForOrganizationUseCase getEndpoint =
        mock(GetWebhookEndpointForOrganizationUseCase.class);
    when(getEndpoint.handle(any())).thenReturn(Optional.empty());
    ListWebhookEndpointsForOrganizationPagedUseCase listEndpointsPaged =
        mock(ListWebhookEndpointsForOrganizationPagedUseCase.class);
    when(listEndpointsPaged.handle(any()))
        .thenReturn(new KeysetPage<WebhookEndpoint>(List.of(), null, null, false, false));

    PlatformWebhookEndpointController endpointController =
        new PlatformWebhookEndpointController(
            mock(RegisterWebhookEndpointUseCase.class),
            getEndpoint,
            listEndpointsPaged,
            mock(DeactivateWebhookEndpointUseCase.class),
            mock(ActivateWebhookEndpointUseCase.class),
            mock(RotateWebhookEndpointSecretUseCase.class),
            mock(UpdateWebhookEndpointUrlUseCase.class),
            mock(UpdateWebhookEndpointDescriptionUseCase.class),
            mock(UpdateWebhookEndpointEventTypesUseCase.class),
            organizationResolver,
            currentPlatformAccount);

    ListWebhookDeliveriesForOrganizationPagedUseCase listDeliveriesPaged =
        mock(ListWebhookDeliveriesForOrganizationPagedUseCase.class);
    when(listDeliveriesPaged.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(), null, null, false, false));
    ListWebhookEndpointsForOrganizationUseCase listEndpoints =
        mock(ListWebhookEndpointsForOrganizationUseCase.class);
    when(listEndpoints.handle(any())).thenReturn(List.of());
    PlatformWebhookLogsController logsController =
        new PlatformWebhookLogsController(
            listDeliveriesPaged,
            mock(ReplayWebhookDeliveryUseCase.class),
            listEndpoints,
            organizationResolver,
            currentPlatformAccount);

    GetWebhookDeliveryActivityForOrganizationUseCase getActivity =
        mock(GetWebhookDeliveryActivityForOrganizationUseCase.class);
    when(getActivity.handle(any())).thenReturn(new WebhookDeliveryActivity(0, 0, List.of()));
    PlatformWebhookActivityController activityController =
        new PlatformWebhookActivityController(
            getActivity, organizationResolver, currentPlatformAccount);

    PlatformWebhookEventCatalogController eventCatalogController =
        new PlatformWebhookEventCatalogController(organizationResolver, currentPlatformAccount);

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
                endpointController, logsController, activityController, eventCatalogController)
            .setViewResolvers(viewResolver)
            .build();
  }

  private String basePath() {
    return "/platform/dashboard/organizations/" + organizationId + "/webhook-endpoints";
  }

  @Test
  void newRouteReachesTheRegisterFormNotTheDetailPage() throws Exception {
    mockMvc
        .perform(get(basePath() + "/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/register-webhook-endpoint"));
  }

  @Test
  void logsRouteReachesTheLogsControllerNotTheDetailPage() throws Exception {
    mockMvc
        .perform(get(basePath() + "/logs"))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/webhook-logs"));
  }

  @Test
  void activityRouteReachesTheActivityControllerNotTheDetailPage() throws Exception {
    mockMvc
        .perform(get(basePath() + "/activity"))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/webhook-activity"));
  }

  @Test
  void eventCatalogRouteReachesTheEventCatalogControllerNotTheDetailPage() throws Exception {
    mockMvc
        .perform(get(basePath() + "/event-catalog"))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/webhook-event-catalog"));
  }

  // A genuine UUID still reaches the detail page's own 404-on-unknown-endpoint behavior, proving
  // the literal siblings above didn't accidentally swallow it the other way around either.
  @Test
  void aRealEndpointIdStillReachesTheDetailController() throws Exception {
    mockMvc.perform(get(basePath() + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }
}

package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.webhook.application.usecase.getwebhookdeliveryactivityfororganization.GetWebhookDeliveryActivityForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.getwebhookdeliveryactivityfororganization.WebhookDeliveryActivity;
import com.clavaris.webhook.application.usecase.getwebhookdeliveryactivityfororganization.WebhookDeliveryActivity.HourlyBucket;
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

class PlatformWebhookActivityControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetWebhookDeliveryActivityForOrganizationUseCase getActivity;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    getActivity = mock(GetWebhookDeliveryActivityForOrganizationUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(getActivity.handle(any()))
        .thenReturn(
            new WebhookDeliveryActivity(3, 1, List.of(new HourlyBucket(Instant.now(), 3, 1))));

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
                new PlatformWebhookActivityController(
                    getActivity, organizationResolver, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String basePath() {
    return "/platform/dashboard/organizations/" + organizationId + "/webhook-endpoints/activity";
  }

  @Test
  void showsTheActivitySummary() throws Exception {
    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(view().name("webhook/platform/webhook-activity"))
        .andExpect(model().attribute("organizationName", "Acme Co"))
        .andExpect(model().attribute("maxBucketTotal", 4L));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath())).andExpect(status().isNotFound());
  }

  @Test
  void rendersCleanlyWithNoAttemptsInTheWindow() throws Exception {
    when(getActivity.handle(any())).thenReturn(new WebhookDeliveryActivity(0, 0, List.of()));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(model().attribute("maxBucketTotal", 0L));
  }
}

package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.organization.application.usecase.getorganizationapikeys.GetOrganizationApiKeysUseCase;
import com.clavaris.organization.application.usecase.getorganizationapikeys.OrganizationApiKeys;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.domain.model.Organization;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Same standalone MockMvc + real Thymeleaf setup as {@link PlatformRateLimitPolicyControllerTest} —
 * a real {@link SpringResourceTemplateResolver}/{@link ThymeleafViewResolver}, so a test run is
 * also a template-syntax smoke test for {@code organization-api-keys.html}.
 */
class PlatformOrganizationApiKeysControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private GetOrganizationApiKeysUseCase getApiKeys;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private Organization organization;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    getApiKeys = mock(GetOrganizationApiKeysUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme Co", OWNER_ID);

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));

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
                new PlatformOrganizationApiKeysController(
                    getOrganization, getApiKeys, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/" + organization.id() + "/api-keys";
  }

  private OrganizationApiKeys sampleApiKeys() {
    return new OrganizationApiKeys(
        "pk_test_abc123",
        "https://clavaris.example.com/o/" + organization.id(),
        "https://clavaris.example.com/oauth2/token",
        "https://clavaris.example.com/o/" + organization.id() + "/oauth2/jwks",
        "-----BEGIN PUBLIC KEY-----\nMIIBIjANBg...\n-----END PUBLIC KEY-----",
        "v1",
        "v1");
  }

  @Test
  void getShowsTheOrganizationsApiKeys() throws Exception {
    OrganizationApiKeys apiKeys = sampleApiKeys();
    when(getApiKeys.handle(organization.id())).thenReturn(Optional.of(apiKeys));

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-api-keys"))
        .andExpect(model().attribute("apiKeys", apiKeys))
        .andExpect(model().attribute("organizationName", "Acme Co"))
        .andExpect(content().string(Matchers.containsString("pk_test_abc123")))
        .andExpect(content().string(Matchers.containsString("-----BEGIN PUBLIC KEY-----")))
        .andExpect(
            content().string(Matchers.containsString("rotating the signing key will break")));
  }

  @Test
  void getReturnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
  }

  // Not expected on a real Organization (BR-ORG-06 guarantees an active signing key from
  // creation) — proves the controller reacts with a loud 404 rather than an NPE if that guarantee
  // is ever violated, same defensive posture as every other "not expected" fallback in this
  // codebase.
  @Test
  void getReturnsNotFoundWhenApiKeysCannotBeResolvedForAnOwnedOrganization() throws Exception {
    when(getApiKeys.handle(organization.id())).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
  }

  @Test
  void rendersAnEmptyStateWhenNoActiveSigningKeyPublicKeyIsAvailable() throws Exception {
    OrganizationApiKeys apiKeysWithoutSigningKey =
        new OrganizationApiKeys(
            "pk_test_abc123",
            "https://clavaris.example.com/o/" + organization.id(),
            "https://clavaris.example.com/oauth2/token",
            "https://clavaris.example.com/o/" + organization.id() + "/oauth2/jwks",
            null,
            "v1",
            "v1");
    when(getApiKeys.handle(organization.id())).thenReturn(Optional.of(apiKeysWithoutSigningKey));

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    Matchers.containsString("No active signing key found for this Organization")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("BEGIN PUBLIC KEY"))))
        .andExpect(
            content()
                .string(
                    Matchers.not(Matchers.containsString("rotating the signing key will break"))));
  }
}

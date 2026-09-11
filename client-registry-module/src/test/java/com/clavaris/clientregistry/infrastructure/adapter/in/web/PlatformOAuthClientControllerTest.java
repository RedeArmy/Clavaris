package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.clientregistry.application.usecase.listoauthclients.ListOAuthClientsUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientUseCase;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Same standalone MockMvc + real Thymeleaf setup as {@code
 * PlatformOrganizationClientControllerTest}. No deactivate/rotate-secret tests here — see {@code
 * PlatformOAuthClientController}'s own Javadoc for why those actions don't exist for this domain
 * type.
 */
class PlatformOAuthClientControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private RegisterOAuthClientUseCase registerClient;
  private ListOAuthClientsUseCase listClients;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    registerClient = mock(RegisterOAuthClientUseCase.class);
    listClients = mock(ListOAuthClientsUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(listClients.handle(any())).thenReturn(List.of());

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
                new PlatformOAuthClientController(
                    registerClient, listClients, organizationResolver, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String basePath() {
    return "/platform/dashboard/organizations/" + organizationId + "/oauth-clients";
  }

  private OAuthClient sampleClient() {
    return OAuthClient.register(
        organizationId,
        "test_abc",
        "hashed-secret",
        List.of("https://jobseeker.example.com/callback"),
        List.of(OAuthGrantTypeOptions.AUTHORIZATION_CODE),
        List.of("openid"),
        true,
        List.of());
  }

  @Test
  void showsTheOrganizationsClients() throws Exception {
    when(listClients.handle(organizationId)).thenReturn(List.of(sampleClient()));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/organization-oauth-clients"))
        .andExpect(model().attribute("organizationName", "Acme Co"));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath())).andExpect(status().isNotFound());
  }

  @Test
  void plainCreatePostRendersThePageDirectlyWithTheOneTimeSecretNeverARedirect() throws Exception {
    OAuthClient created = sampleClient();
    when(registerClient.handle(any()))
        .thenReturn(new RegisterOAuthClientResult(created, "raw-secret-shown-once"));

    mockMvc
        .perform(
            post(basePath())
                .param("redirectUris", "https://jobseeker.example.com/callback")
                .param("allowedGrantTypes", OAuthGrantTypeOptions.AUTHORIZATION_CODE))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/organization-oauth-clients"))
        .andExpect(model().attribute("justRegisteredRawSecret", "raw-secret-shown-once"));

    Mockito.verify(registerClient).handle(any());
  }

  @Test
  void htmxCreatePostReturnsTheClientsFragment() throws Exception {
    OAuthClient created = sampleClient();
    when(registerClient.handle(any()))
        .thenReturn(new RegisterOAuthClientResult(created, "raw-secret-shown-once"));

    mockMvc
        .perform(
            post(basePath())
                .param("redirectUris", "https://jobseeker.example.com/callback")
                .param("allowedGrantTypes", OAuthGrantTypeOptions.AUTHORIZATION_CODE)
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/organization-oauth-clients :: clients"));
  }

  @Test
  void createWithNoRedirectUrisRendersAnErrorWithoutCreatingAnything() throws Exception {
    mockMvc
        .perform(
            post(basePath()).param("allowedGrantTypes", OAuthGrantTypeOptions.AUTHORIZATION_CODE))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/organization-oauth-clients"));

    Mockito.verify(registerClient, never()).handle(any());
  }

  @Test
  void createWithNoGrantTypesSelectedRendersAnErrorWithoutCreatingAnything() throws Exception {
    mockMvc
        .perform(post(basePath()).param("redirectUris", "https://jobseeker.example.com/callback"))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/organization-oauth-clients"));

    Mockito.verify(registerClient, never()).handle(any());
  }
}

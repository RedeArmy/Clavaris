package com.clavaris.clientregistry.infrastructure.adapter.in.web;

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

import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientResult;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientUseCase;
import com.clavaris.clientregistry.application.usecase.deactivateorganizationclient.DeactivateOrganizationClientUseCase;
import com.clavaris.clientregistry.application.usecase.listorganizationclients.ListOrganizationClientsUseCase;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretUseCase;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
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
 * Same standalone MockMvc + real Thymeleaf setup as organization-module's own {@code
 * PlatformWorkspaceControllerTest}.
 */
class PlatformOrganizationClientControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private CreateOrganizationClientUseCase createClient;
  private ListOrganizationClientsUseCase listClients;
  private DeactivateOrganizationClientUseCase deactivateClient;
  private RotateOrganizationClientSecretUseCase rotateClientSecret;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    createClient = mock(CreateOrganizationClientUseCase.class);
    listClients = mock(ListOrganizationClientsUseCase.class);
    deactivateClient = mock(DeactivateOrganizationClientUseCase.class);
    rotateClientSecret = mock(RotateOrganizationClientSecretUseCase.class);
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
                new PlatformOrganizationClientController(
                    createClient,
                    listClients,
                    deactivateClient,
                    rotateClientSecret,
                    organizationResolver,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String basePath() {
    return "/platform/dashboard/organizations/" + organizationId + "/secret-keys";
  }

  private OrganizationClient sampleClient() {
    return OrganizationClient.register(
        organizationId, "sk_test_abc", "hashed-secret", List.of(PlatformScopes.WORKSPACES_WRITE));
  }

  @Test
  void showsTheOrganizationsClients() throws Exception {
    when(listClients.handle(organizationId)).thenReturn(List.of(sampleClient()));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/organization-secret-keys"))
        .andExpect(model().attribute("organizationName", "Acme Co"));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath())).andExpect(status().isNotFound());
  }

  @Test
  void plainCreatePostRendersThePageDirectlyWithTheOneTimeSecretNeverARedirect() throws Exception {
    OrganizationClient created = sampleClient();
    when(createClient.handle(any()))
        .thenReturn(new CreateOrganizationClientResult(created, "raw-secret-shown-once"));

    mockMvc
        .perform(post(basePath()).param("allowedScopes", PlatformScopes.WORKSPACES_WRITE))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/organization-secret-keys"))
        .andExpect(model().attribute("justCreatedRawSecret", "raw-secret-shown-once"));

    verify(createClient).handle(any());
  }

  @Test
  void htmxCreatePostReturnsTheClientsFragment() throws Exception {
    OrganizationClient created = sampleClient();
    when(createClient.handle(any()))
        .thenReturn(new CreateOrganizationClientResult(created, "raw-secret-shown-once"));

    mockMvc
        .perform(
            post(basePath())
                .param("allowedScopes", PlatformScopes.WORKSPACES_WRITE)
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/organization-secret-keys :: clients"));
  }

  @Test
  void createWithNoScopesSelectedRendersAnErrorWithoutCreatingAnything() throws Exception {
    mockMvc
        .perform(post(basePath()))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/organization-secret-keys"));

    verify(createClient, never()).handle(any());
  }

  @Test
  void plainDeactivatePostRedirectsOnSuccess() throws Exception {
    OrganizationClient client = sampleClient();
    when(listClients.handle(organizationId)).thenReturn(List.of(client));

    mockMvc
        .perform(post(basePath() + "/" + client.clientId() + "/deactivate"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath()));

    verify(deactivateClient).handle(any());
  }

  @Test
  void deactivateReturnsNotFoundWhenTheClientBelongsToADifferentOrganization() throws Exception {
    when(listClients.handle(organizationId)).thenReturn(List.of());

    mockMvc
        .perform(post(basePath() + "/sk_test_someone_elses/deactivate"))
        .andExpect(status().isNotFound());

    verify(deactivateClient, never()).handle(any());
  }

  @Test
  void plainRotateSecretPostRendersThePageDirectlyWithTheNewSecretNeverARedirect()
      throws Exception {
    OrganizationClient client = sampleClient();
    when(listClients.handle(organizationId)).thenReturn(List.of(client));
    when(rotateClientSecret.handle(any()))
        .thenReturn(new RotateOrganizationClientSecretResult(client.clientId(), "new-raw-secret"));

    mockMvc
        .perform(post(basePath() + "/" + client.clientId() + "/rotate-secret"))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/organization-secret-keys"))
        .andExpect(model().attribute("justCreatedRawSecret", "new-raw-secret"));
  }

  @Test
  void rotateSecretReturnsNotFoundWhenTheClientBelongsToADifferentOrganization() throws Exception {
    when(listClients.handle(organizationId)).thenReturn(List.of());

    mockMvc
        .perform(post(basePath() + "/sk_test_someone_elses/rotate-secret"))
        .andExpect(status().isNotFound());

    verify(rotateClientSecret, never()).handle(any());
  }
}

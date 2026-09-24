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

import com.clavaris.clientregistry.application.usecase.deleteoauthclient.DeleteOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.getoauthclientfororganization.GetOAuthClientForOrganizationUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientDefaults;
import com.clavaris.clientregistry.domain.model.OAuthClient;
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
 * Same standalone MockMvc + real Thymeleaf setup, and same real-{@code HttpSession}-carries-across-
 * requests shape, as {@code organization.infrastructure.adapter.in.web.
 * PlatformDeleteOrganizationControllerTest} — see that class's own Javadoc.
 */
class PlatformDeleteOAuthClientControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOAuthClientForOrganizationUseCase getClient;
  private DeleteOAuthClientUseCase deleteClient;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    getClient = mock(GetOAuthClientForOrganizationUseCase.class);
    deleteClient = mock(DeleteOAuthClientUseCase.class);
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
                new PlatformDeleteOAuthClientController(
                    getClient, deleteClient, organizationResolver, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private OAuthClient inactiveClient() {
    OAuthClient active =
        OAuthClient.register(
            organizationId,
            "client_abc",
            "hashed-secret",
            List.of("https://jobseeker.example.com/callback"),
            OAuthClientDefaults.GRANT_TYPES,
            OAuthClientDefaults.SCOPES,
            true,
            List.of());
    return active.deactivate();
  }

  private String basePath(final String clientId) {
    return "/platform/dashboard/organizations/"
        + organizationId
        + "/oauth-clients/"
        + clientId
        + "/delete";
  }

  @Test
  void showsTheConfirmationPageWithAFreshTokenForAnInactiveClient() throws Exception {
    OAuthClient client = inactiveClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(get(basePath(client.clientId())))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/delete-oauth-client-confirm"))
        .andExpect(model().attribute("client", client))
        .andExpect(model().attributeExists("confirmationToken"));
  }

  // Live UX request, 2026-09-24: permanent deletion only ever reachable once the client is
  // already inactive — the link to this page is itself hidden while active, and a direct GET
  // redirects back to the detail page instead of rendering the confirm form.
  @Test
  void redirectsBackToTheDetailPageForAnActiveClient() throws Exception {
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "client_abc",
            "hashed-secret",
            List.of("https://jobseeker.example.com/callback"),
            OAuthClientDefaults.GRANT_TYPES,
            OAuthClientDefaults.SCOPES,
            true,
            List.of());
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(get(basePath(client.clientId())))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl(
                "/platform/dashboard/organizations/"
                    + organizationId
                    + "/oauth-clients/"
                    + client.clientId()));

    verify(deleteClient, never()).handle(any());
  }

  @Test
  void returnsNotFoundWhenTheClientBelongsToADifferentOrganization() throws Exception {
    when(getClient.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath("client_someone_elses"))).andExpect(status().isNotFound());
  }

  @Test
  void aValidTokenAndTheLiteralWordDeleteDeletesAndRedirectsToTheList() throws Exception {
    OAuthClient client = inactiveClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));
    MvcResult confirmPage = mockMvc.perform(get(basePath(client.clientId()))).andReturn();
    String token = (String) confirmPage.getModelAndView().getModel().get("confirmationToken");

    mockMvc
        .perform(
            post(basePath(client.clientId()))
                .session((MockHttpSession) confirmPage.getRequest().getSession())
                .param("confirmationToken", token)
                .param("confirmedText", "delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl(
                "/platform/dashboard/organizations/" + organizationId + "/oauth-clients"));

    verify(deleteClient).handle(any());
  }

  // Deliberate widening beyond PlatformDeleteOrganizationController's own single-string
  // precedent — see PlatformDeleteOAuthClientController's own Javadoc.
  @Test
  void aValidTokenAndTheTypedClientIdAlsoDeletes() throws Exception {
    OAuthClient client = inactiveClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));
    MvcResult confirmPage = mockMvc.perform(get(basePath(client.clientId()))).andReturn();
    String token = (String) confirmPage.getModelAndView().getModel().get("confirmationToken");

    mockMvc
        .perform(
            post(basePath(client.clientId()))
                .session((MockHttpSession) confirmPage.getRequest().getSession())
                .param("confirmationToken", token)
                .param("confirmedText", client.clientId()))
        .andExpect(status().is3xxRedirection());

    verify(deleteClient).handle(any());
  }

  @Test
  void aTokenCannotBeReusedForASecondDeleteAttempt() throws Exception {
    OAuthClient client = inactiveClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));
    MvcResult confirmPage = mockMvc.perform(get(basePath(client.clientId()))).andReturn();
    String token = (String) confirmPage.getModelAndView().getModel().get("confirmationToken");
    MockHttpSession session = (MockHttpSession) confirmPage.getRequest().getSession();

    mockMvc.perform(
        post(basePath(client.clientId()))
            .session(session)
            .param("confirmationToken", token)
            .param("confirmedText", "delete"));

    mockMvc
        .perform(
            post(basePath(client.clientId()))
                .session(session)
                .param("confirmationToken", token)
                .param("confirmedText", "delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/delete-oauth-client-confirm"))
        .andExpect(model().attribute("confirmationExpiredError", true));

    verify(deleteClient).handle(any());
  }

  @Test
  void aWrongTokenRendersAnInlineErrorWithoutDeletingAnything() throws Exception {
    OAuthClient client = inactiveClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));
    MvcResult confirmPage = mockMvc.perform(get(basePath(client.clientId()))).andReturn();
    MockHttpSession session = (MockHttpSession) confirmPage.getRequest().getSession();

    mockMvc
        .perform(
            post(basePath(client.clientId()))
                .session(session)
                .param("confirmationToken", "not-the-real-token")
                .param("confirmedText", "delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/delete-oauth-client-confirm"))
        .andExpect(model().attribute("confirmationExpiredError", true));

    verify(deleteClient, never()).handle(any());
  }

  @Test
  void aMismatchedConfirmationRendersAnInlineErrorWithoutDeletingAnything() throws Exception {
    OAuthClient client = inactiveClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));
    MvcResult confirmPage = mockMvc.perform(get(basePath(client.clientId()))).andReturn();
    String token = (String) confirmPage.getModelAndView().getModel().get("confirmationToken");
    MockHttpSession session = (MockHttpSession) confirmPage.getRequest().getSession();

    mockMvc
        .perform(
            post(basePath(client.clientId()))
                .session(session)
                .param("confirmationToken", token)
                .param("confirmedText", "not the right thing"))
        .andExpect(status().isOk())
        .andExpect(view().name("clientregistry/platform/delete-oauth-client-confirm"))
        .andExpect(model().attribute("confirmationMismatchError", true));

    verify(deleteClient, never()).handle(any());
  }
}

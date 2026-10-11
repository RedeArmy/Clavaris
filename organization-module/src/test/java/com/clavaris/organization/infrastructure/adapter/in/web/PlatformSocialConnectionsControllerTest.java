package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.clavaris.organization.application.usecase.deleteorganizationsocialcredential.DeleteOrganizationSocialCredentialCommand;
import com.clavaris.organization.application.usecase.deleteorganizationsocialcredential.DeleteOrganizationSocialCredentialUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getsocialconnections.GetSocialConnectionsUseCase;
import com.clavaris.organization.application.usecase.getsocialconnections.SocialConnection;
import com.clavaris.organization.application.usecase.getsocialconnections.SocialConnectionStatus;
import com.clavaris.organization.application.usecase.getsocialconnections.SocialConnections;
import com.clavaris.organization.application.usecase.savesocialconnection.CredentialsRequiredException;
import com.clavaris.organization.application.usecase.savesocialconnection.CustomCredentialsNotAllowedException;
import com.clavaris.organization.application.usecase.savesocialconnection.IncompleteCredentialsException;
import com.clavaris.organization.application.usecase.savesocialconnection.SaveSocialConnectionCommand;
import com.clavaris.organization.application.usecase.savesocialconnection.SaveSocialConnectionUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import com.clavaris.organization.domain.model.SocialProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Same standalone MockMvc + real Thymeleaf setup as the other Configure pages' tests: the pages
 * really render, so a template that no longer parses, or that shows a secret, fails here.
 */
class PlatformSocialConnectionsControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private GetSocialConnectionsUseCase getConnections;
  private SaveSocialConnectionUseCase saveConnection;
  private DeleteOrganizationSocialCredentialUseCase deleteCredentials;
  private MockMvc mockMvc;
  private Organization organization;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    getConnections = mock(GetSocialConnectionsUseCase.class);
    saveConnection = mock(SaveSocialConnectionUseCase.class);
    deleteCredentials = mock(DeleteOrganizationSocialCredentialUseCase.class);
    final CurrentPlatformAccountResolver currentPlatformAccount =
        mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme Co", OWNER_ID);
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(getConnections.handle(organization.id()))
        .thenReturn(connections(OrganizationEnvironment.DEVELOPMENT, false));

    final GenericApplicationContext applicationContext = new GenericApplicationContext();
    applicationContext.refresh();
    final SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
    templateResolver.setApplicationContext(applicationContext);
    templateResolver.setPrefix("classpath:/templates/");
    templateResolver.setSuffix(".html");
    final SpringTemplateEngine templateEngine = new SpringTemplateEngine();
    templateEngine.setTemplateResolver(templateResolver);
    final ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
    viewResolver.setTemplateEngine(templateEngine);
    viewResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformSocialConnectionsController(
                    getOrganization,
                    getConnections,
                    saveConnection,
                    deleteCredentials,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private SocialConnections connections(
      final OrganizationEnvironment environment, final boolean googleHasCredentials) {
    return new SocialConnections(
        environment,
        List.of(
            new SocialConnection(
                SocialProvider.GOOGLE,
                googleHasCredentials
                    ? SocialConnectionStatus.ENABLED
                    : SocialConnectionStatus.DISABLED,
                googleHasCredentials,
                googleHasCredentials,
                googleHasCredentials ? Optional.of("my-client-id") : Optional.empty(),
                false,
                false,
                "https://id.example/login/oauth2/code/google"),
            new SocialConnection(
                SocialProvider.GITHUB,
                SocialConnectionStatus.SETUP_REQUIRED,
                true,
                false,
                Optional.empty(),
                false,
                false,
                "https://id.example/login/oauth2/code/github")));
  }

  private String base() {
    return "/platform/dashboard/organizations/" + organization.id() + "/connections";
  }

  @Test
  void theListShowsEveryProviderWithItsStatus() throws Exception {
    final String html =
        mockMvc
            .perform(get(base()))
            .andExpect(status().isOk())
            .andExpect(view().name("organization/platform/organization-connections"))
            .andExpect(model().attributeExists("connections"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("Google", "GitHub", "Disabled", "Setup required");
    assertThat(html).contains(base() + "/google", base() + "/github");
    // Development: the shared apps are used, so the page says there is nothing to set up.
    assertThat(html).contains("shared Google and GitHub apps");
  }

  @Test
  void aProductionListDoesNotShowTheDevelopmentNote() throws Exception {
    when(getConnections.handle(organization.id()))
        .thenReturn(connections(OrganizationEnvironment.PRODUCTION, true));

    final String html =
        mockMvc
            .perform(get(base()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).doesNotContain("shared Google and GitHub apps");
    assertThat(html).contains("Used for sign-in");
  }

  @Test
  void aDevelopmentProviderPageHasNoCredentialFields() throws Exception {
    final String html =
        mockMvc
            .perform(get(base() + "/google"))
            .andExpect(status().isOk())
            .andExpect(view().name("organization/platform/organization-connection-detail"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("Enable for sign-up and sign-in", "Block email subaddresses");
    assertThat(html).contains("Always show account selector prompt");
    assertThat(html).doesNotContain("name=\"clientId\"").doesNotContain("name=\"clientSecret\"");
    assertThat(html).contains("shared").contains("Promote to production");
  }

  // Once promoted, the production Organization is where the connection is set up.
  @Test
  void aPromotedDevelopmentOrganizationPointsToItsProductionOne() throws Exception {
    final UUID productionId = UUID.randomUUID();
    final Organization promoted =
        Organization.reconstitute(
            organization.id(),
            organization.name(),
            organization.createdAt(),
            organization.ownerPlatformAccountId(),
            false,
            List.of(),
            OrganizationEnvironment.DEVELOPMENT,
            productionId);
    when(getOrganization.handle(any())).thenReturn(Optional.of(promoted));

    final String html =
        mockMvc
            .perform(get(base() + "/google"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("/platform/dashboard/organizations/" + productionId + "/connections/google")
        .contains("Set up this connection in the production Organization")
        .doesNotContain("promote-to-production");
  }

  @Test
  void aDevelopmentOrganizationThatWasNeverPromotedOffersToPromote() throws Exception {
    final String html =
        mockMvc
            .perform(get(base() + "/google"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("/promote-to-production").contains("Promote to production");
    assertThat(html).doesNotContain("Set up this connection in the production Organization");
  }

  @Test
  void aProductionProviderPageShowsTheRedirectUriAndNeverTheSecret() throws Exception {
    when(getConnections.handle(organization.id()))
        .thenReturn(connections(OrganizationEnvironment.PRODUCTION, true));

    final String html =
        mockMvc
            .perform(get(base() + "/google"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("https://id.example/login/oauth2/code/google");
    assertThat(html).contains("my-client-id").contains("name=\"clientSecret\"");
    // The secret input is never pre-filled, and nothing in the page can hold a secret.
    assertThat(html).doesNotContain("encrypted");
    assertThat(html).contains("Remove credentials");
  }

  @Test
  void aProductionProviderWithoutCredentialsHasNoRemoveButton() throws Exception {
    when(getConnections.handle(organization.id()))
        .thenReturn(connections(OrganizationEnvironment.PRODUCTION, false));

    final String html =
        mockMvc
            .perform(get(base() + "/github"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).doesNotContain("Remove credentials");
    assertThat(html).contains("not offered on the sign-in page");
  }

  @Test
  void anUnknownProviderIsNotAPage() throws Exception {
    mockMvc.perform(get(base() + "/facebook")).andExpect(status().isNotFound());
  }

  @Test
  void anOrganizationThatIsNotTheCallersIsNotFound() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(base())).andExpect(status().isNotFound());
    mockMvc.perform(get(base() + "/google")).andExpect(status().isNotFound());
    mockMvc
        .perform(post(base() + "/google").param("enabled", "true"))
        .andExpect(status().isNotFound());
    mockMvc.perform(post(base() + "/google/credentials/remove")).andExpect(status().isNotFound());

    verify(saveConnection, never()).handle(any());
    verify(deleteCredentials, never()).handle(any());
  }

  @Test
  void savingSendsTheFormToTheUseCaseAndReturnsToThePage() throws Exception {
    mockMvc
        .perform(
            post(base() + "/google")
                .param("enabled", "true")
                .param("clientId", "my-client-id")
                .param("clientSecret", "my-secret")
                .param("blockEmailSubaddresses", "true"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(base() + "/google?saved"));

    final ArgumentCaptor<SaveSocialConnectionCommand> command =
        ArgumentCaptor.forClass(SaveSocialConnectionCommand.class);
    verify(saveConnection).handle(command.capture());
    assertThat(command.getValue().provider()).isEqualTo(SocialProvider.GOOGLE);
    assertThat(command.getValue().enabled()).isTrue();
    assertThat(command.getValue().clientId()).isEqualTo("my-client-id");
    assertThat(command.getValue().rawClientSecret()).isEqualTo("my-secret");
    assertThat(command.getValue().blockEmailSubaddresses()).isTrue();
    assertThat(command.getValue().alwaysShowAccountSelector()).isFalse();
  }

  @Test
  void anUncheckedSwitchSavesAsOff() throws Exception {
    mockMvc.perform(post(base() + "/github")).andExpect(status().is3xxRedirection());

    final ArgumentCaptor<SaveSocialConnectionCommand> command =
        ArgumentCaptor.forClass(SaveSocialConnectionCommand.class);
    verify(saveConnection).handle(command.capture());
    assertThat(command.getValue().enabled()).isFalse();
  }

  @Test
  void enablingWithoutCredentialsShowsThePageAgainWithTheReason() throws Exception {
    when(getConnections.handle(organization.id()))
        .thenReturn(connections(OrganizationEnvironment.PRODUCTION, false));
    org.mockito.Mockito.doThrow(
            new CredentialsRequiredException(organization.id(), SocialProvider.GOOGLE))
        .when(saveConnection)
        .handle(any());

    final String html =
        mockMvc
            .perform(post(base() + "/google").param("enabled", "true"))
            .andExpect(status().isOk())
            .andExpect(view().name("organization/platform/organization-connection-detail"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // The page escapes the apostrophe (&#39;), so compare what a person reads.
    assertThat(HtmlUtils.htmlUnescape(html))
        .contains("Enter this provider's client ID and client secret to enable it");
  }

  @Test
  void aHalfEnteredCredentialShowsThePageAgainWithTheReasonAndNeverEchoesTheSecret()
      throws Exception {
    when(getConnections.handle(organization.id()))
        .thenReturn(connections(OrganizationEnvironment.PRODUCTION, false));
    org.mockito.Mockito.doThrow(new IncompleteCredentialsException())
        .when(saveConnection)
        .handle(any());

    final String html =
        mockMvc
            .perform(
                post(base() + "/google")
                    .param("clientId", "only-id")
                    .param("clientSecret", "typed-secret"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("Enter both the client ID and the client secret");
    assertThat(html).contains("only-id").doesNotContain("typed-secret");
  }

  @Test
  void credentialsForADevelopmentOrganizationAreRefusedWithTheReason() throws Exception {
    org.mockito.Mockito.doThrow(new CustomCredentialsNotAllowedException(organization.id()))
        .when(saveConnection)
        .handle(any());

    final String html =
        mockMvc
            .perform(post(base() + "/google").param("clientId", "x").param("clientSecret", "y"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(HtmlUtils.htmlUnescape(html))
        .contains("A development Organization signs in through Clavaris's shared app");
  }

  @Test
  void anOverLongClientIdIsRefusedBeforeTheUseCase() throws Exception {
    mockMvc
        .perform(post(base() + "/google").param("clientId", "x".repeat(256)))
        .andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("connectionForm", "clientId"));

    verify(saveConnection, never()).handle(any());
  }

  @Test
  void removingCredentialsCallsTheUseCaseAndReturnsToThePage() throws Exception {
    mockMvc
        .perform(post(base() + "/github/credentials/remove"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(base() + "/github?removed"));

    final ArgumentCaptor<DeleteOrganizationSocialCredentialCommand> command =
        ArgumentCaptor.forClass(DeleteOrganizationSocialCredentialCommand.class);
    verify(deleteCredentials).handle(command.capture());
    assertThat(command.getValue().provider()).isEqualTo(SocialProvider.GITHUB);
    assertThat(command.getValue().organizationId()).isEqualTo(organization.id());
  }
}

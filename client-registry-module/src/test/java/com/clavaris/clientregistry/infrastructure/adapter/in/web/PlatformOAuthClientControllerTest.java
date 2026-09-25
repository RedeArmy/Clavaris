package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.clientregistry.application.usecase.activateoauthclient.ActivateOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.DeactivateOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.getoauthclientfororganization.GetOAuthClientForOrganizationQuery;
import com.clavaris.clientregistry.application.usecase.getoauthclientfororganization.GetOAuthClientForOrganizationUseCase;
import com.clavaris.clientregistry.application.usecase.listoauthclientspaged.ListOAuthClientsPagedQuery;
import com.clavaris.clientregistry.application.usecase.listoauthclientspaged.ListOAuthClientsPagedUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientDefaults;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientconsent.UpdateOAuthClientConsentUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes.UpdateOAuthClientGrantTypesUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings.OAuthClientInactiveException;
import com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings.UpdateOAuthClientRedirectSettingsUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientscopes.UpdateOAuthClientScopesUseCase;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Same standalone MockMvc + real Thymeleaf setup as {@code
 * PlatformOrganizationClientControllerTest}. Rewritten alongside the controller's own SDE-III
 * refactor, 2026-09-23 (BR-ORG-06, Clerk-parity master-detail redesign) — "Add client" no longer
 * takes a request body, and rotate-secret/deactivate/redirect-settings now all live on the new
 * per-client detail page, not the list.
 */
class PlatformOAuthClientControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final String LIST_VIEW = "clientregistry/platform/organization-oauth-clients";
  private static final String DETAIL_VIEW =
      "clientregistry/platform/organization-oauth-client-detail";

  private RegisterOAuthClientUseCase registerClient;
  private ListOAuthClientsPagedUseCase listClientsPaged;
  private GetOAuthClientForOrganizationUseCase getClient;
  private DeactivateOAuthClientUseCase deactivateClient;
  private ActivateOAuthClientUseCase activateClient;
  private RotateOAuthClientSecretUseCase rotateClientSecret;
  private UpdateOAuthClientRedirectSettingsUseCase updateRedirectSettings;
  private UpdateOAuthClientGrantTypesUseCase updateGrantTypes;
  private UpdateOAuthClientScopesUseCase updateScopes;
  private UpdateOAuthClientConsentUseCase updateConsent;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    registerClient = mock(RegisterOAuthClientUseCase.class);
    listClientsPaged = mock(ListOAuthClientsPagedUseCase.class);
    getClient = mock(GetOAuthClientForOrganizationUseCase.class);
    deactivateClient = mock(DeactivateOAuthClientUseCase.class);
    activateClient = mock(ActivateOAuthClientUseCase.class);
    rotateClientSecret = mock(RotateOAuthClientSecretUseCase.class);
    updateRedirectSettings = mock(UpdateOAuthClientRedirectSettingsUseCase.class);
    updateGrantTypes = mock(UpdateOAuthClientGrantTypesUseCase.class);
    updateScopes = mock(UpdateOAuthClientScopesUseCase.class);
    updateConsent = mock(UpdateOAuthClientConsentUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(listClientsPaged.handle(any())).thenReturn(emptyPage());

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
                    registerClient,
                    listClientsPaged,
                    getClient,
                    deactivateClient,
                    activateClient,
                    rotateClientSecret,
                    updateRedirectSettings,
                    updateGrantTypes,
                    updateScopes,
                    updateConsent,
                    organizationResolver,
                    currentPlatformAccount,
                    "https://clavaris.example.test"))
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
        OAuthClientDefaults.GRANT_TYPES,
        OAuthClientDefaults.SCOPES,
        true,
        List.of());
  }

  private static KeysetPage<OAuthClient> emptyPage() {
    return new KeysetPage<>(List.of(), null, null, false, false);
  }

  private static KeysetCursor cursorOf(final OAuthClient client) {
    return new KeysetCursor(client.createdAt(), client.id());
  }

  @Test
  void showsTheOrganizationsClients() throws Exception {
    OAuthClient client = sampleClient();
    KeysetCursor cursor = cursorOf(client);
    when(listClientsPaged.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(client), cursor, cursor, false, false));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(view().name(LIST_VIEW))
        .andExpect(model().attribute("organizationName", "Acme Co"))
        .andExpect(model().attribute("clients", List.of(client)));
  }

  // Live UX request, 2026-09-24 — scoped warning: a client with no redirect URI is a genuinely
  // valid state (client_credentials still works), so the list only flags it, never implies the
  // client itself is broken.
  @Test
  void showsANoRedirectUriBadgeForAClientThatHasNoneConfiguredYet() throws Exception {
    OAuthClient clientWithNoRedirectUri =
        OAuthClient.register(
            organizationId,
            "test_no_redirect",
            "hashed-secret",
            List.of(),
            OAuthClientDefaults.GRANT_TYPES,
            OAuthClientDefaults.SCOPES,
            true,
            List.of());
    KeysetCursor cursor = cursorOf(clientWithNoRedirectUri);
    when(listClientsPaged.handle(any()))
        .thenReturn(
            new KeysetPage<>(List.of(clientWithNoRedirectUri), cursor, cursor, false, false));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("No redirect URI")));
  }

  @Test
  void doesNotShowTheNoRedirectUriBadgeForAClientThatAlreadyHasOne() throws Exception {
    OAuthClient client = sampleClient();
    KeysetCursor cursor = cursorOf(client);
    when(listClientsPaged.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(client), cursor, cursor, false, false));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("No redirect URI"))));
  }

  // TD-PERF-020 (keyset revision): proves ?after= is actually decoded and threaded into the
  // query.
  @Test
  void getPassesTheAfterCursorThroughToTheUseCase() throws Exception {
    KeysetCursor cursor = new KeysetCursor(java.time.Instant.now(), UUID.randomUUID());

    mockMvc.perform(get(basePath()).param("after", cursor.encode()));

    verify(listClientsPaged)
        .handle(new ListOAuthClientsPagedQuery(organizationId, KeysetPageRequest.after(cursor)));
  }

  // TD-PERF-020: an HTMX-originated pagination link (hx-get) must get back just the clients
  // fragment, not the full page.
  @Test
  void htmxGetReturnsTheClientsFragmentInsteadOfTheFullPage() throws Exception {
    mockMvc
        .perform(get(basePath()).header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(LIST_VIEW + " :: clients"));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath())).andExpect(status().isNotFound());
  }

  @Test
  void showsTheClientDetailPage() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(new GetOAuthClientForOrganizationQuery("test_abc", organizationId)))
        .thenReturn(Optional.of(client));

    mockMvc
        .perform(get(basePath() + "/test_abc"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW))
        .andExpect(model().attribute("client", client))
        .andExpect(model().attribute("organizationName", "Acme Co"));
  }

  // Live UX request, 2026-09-24 — reordering: "Redirect settings" moved up to right after
  // "Client credentials" (the one actionable card, not buried under two read-only ones), and a
  // scoped warning appears only when the client genuinely has no redirect URI yet.
  @Test
  void showsTheNoRedirectUriWarningAndPutsRedirectSettingsRightAfterCredentials() throws Exception {
    OAuthClient clientWithNoRedirectUri =
        OAuthClient.register(
            organizationId,
            "test_no_redirect",
            "hashed-secret",
            List.of(),
            OAuthClientDefaults.GRANT_TYPES,
            OAuthClientDefaults.SCOPES,
            true,
            List.of());
    when(getClient.handle(any())).thenReturn(Optional.of(clientWithNoRedirectUri));

    // Stops short of the banner's own em dash — this standalone MockMvc harness (no
    // CharacterEncodingFilter wired, unlike the real app) doesn't decode getContentAsString() as
    // UTF-8 by default, garbling non-ASCII characters; not a real rendering bug (the full app does
    // set UTF-8), just this assertion staying within what the harness renders correctly.
    MvcResult result =
        mockMvc
            .perform(get(basePath() + "/test_no_redirect"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("No redirect URI configured yet")))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    int credentialsIndex = body.indexOf("Client credentials");
    int redirectSettingsIndex = body.indexOf("Redirect settings");
    int setupInstructionsIndex = body.indexOf("Add these to your application");
    int configurationIndex = body.indexOf("<h2>Configuration</h2>");
    assertThat(credentialsIndex).isPositive();
    assertThat(redirectSettingsIndex).isGreaterThan(credentialsIndex);
    assertThat(setupInstructionsIndex).isGreaterThan(redirectSettingsIndex);
    assertThat(configurationIndex).isGreaterThan(setupInstructionsIndex);
  }

  @Test
  void doesNotShowTheNoRedirectUriWarningForAClientThatAlreadyHasOne() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(get(basePath() + "/test_abc"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("No redirect URI configured yet"))));
  }

  // Live UX bug fix, 2026-09-24: the redirect-settings form (and the "no redirect URI" warning,
  // meaningless once nothing can be edited) must be hidden on an inactive client — Rotate secret/
  // Deactivate hide the same way, Activate/Delete permanently appear instead.
  @Test
  void showsActivateAndDeletePermanentlyAndHidesTheRedirectSettingsFormForAnInactiveClient()
      throws Exception {
    OAuthClient client = sampleClient().deactivate();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(get(basePath() + "/" + client.clientId()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(">Activate<")))
        .andExpect(content().string(containsString("Delete permanently")))
        // Not "Save redirect settings" alone — an unrelated HTML comment elsewhere on this page
        // mentions that exact phrase in prose (comments pass through to rendered output,
        // Thymeleaf doesn't strip them), same class of false-positive already found and fixed
        // once this session for a card-reordering test. ">...</button>" only matches the real
        // button tag, not the comment.
        .andExpect(content().string(not(containsString(">Save redirect settings<"))))
        .andExpect(content().string(not(containsString("No redirect URI configured yet"))))
        .andExpect(content().string(not(containsString(">Rotate secret<"))))
        .andExpect(content().string(not(containsString(">Deactivate<"))));
  }

  @Test
  void hidesActivateAndDeletePermanentlyAndShowsTheRedirectSettingsFormForAnActiveClient()
      throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(get(basePath() + "/" + client.clientId()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(">Save redirect settings<")))
        .andExpect(content().string(containsString(">Rotate secret<")))
        .andExpect(content().string(containsString(">Deactivate<")))
        .andExpect(content().string(not(containsString(">Activate<"))))
        .andExpect(content().string(not(containsString("Delete permanently"))));
  }

  @Test
  void htmxGetDetailReturnsTheDetailFragment() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(get(basePath() + "/test_abc").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW + " :: detail"));
  }

  @Test
  void showDetailReturnsNotFoundForAnUnknownOrCrossTenantClientId() throws Exception {
    when(getClient.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath() + "/ghost-client")).andExpect(status().isNotFound());
  }

  // BR-ORG-06: no request body at all — every field is fixed by OAuthClientDefaults.
  @Test
  void createRegistersWithOAuthClientDefaultsAndNoRedirectUrisYet() throws Exception {
    OAuthClient created = sampleClient();
    when(registerClient.handle(any()))
        .thenReturn(new RegisterOAuthClientResult(created, "raw-secret-shown-once"));

    mockMvc
        .perform(post(basePath()))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW))
        .andExpect(model().attribute("justRegisteredRawSecret", "raw-secret-shown-once"))
        .andExpect(model().attribute("justRegisteredClientId", created.clientId()));

    ArgumentCaptor<RegisterOAuthClientCommand> captor =
        ArgumentCaptor.forClass(RegisterOAuthClientCommand.class);
    verify(registerClient).handle(captor.capture());
    RegisterOAuthClientCommand command = captor.getValue();
    assertThat(command.organizationId()).isEqualTo(organizationId);
    assertThat(command.redirectUris()).isEmpty();
    assertThat(command.postLogoutRedirectUris()).isEmpty();
    assertThat(command.allowedGrantTypes()).isEqualTo(OAuthClientDefaults.GRANT_TYPES);
    assertThat(command.allowedScopes()).isEqualTo(OAuthClientDefaults.SCOPES);
    assertThat(command.requireConsent()).isEqualTo(OAuthClientDefaults.REQUIRE_CONSENT);
  }

  // Even an HTMX-originated "Add client" click still lands on the full detail page — see
  // PlatformOAuthClientController's own Javadoc for why (a one-time secret has nowhere safe to
  // go through a fragment scoped to the list's own #clients-content).
  @Test
  void createAlwaysRendersTheFullDetailViewEvenOnAnHtmxRequest() throws Exception {
    OAuthClient created = sampleClient();
    when(registerClient.handle(any()))
        .thenReturn(new RegisterOAuthClientResult(created, "raw-secret-shown-once"));

    mockMvc
        .perform(post(basePath()).header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW));
  }

  // Clerk-style "add these to your app" panel — proves the model carries every endpoint a real
  // OIDC client library needs, built from CLAVARIS_BASE_URL, not guessed at.
  @Test
  void createRendersSetupInstructionsForTheConsumerApp() throws Exception {
    OAuthClient created = sampleClient();
    when(registerClient.handle(any()))
        .thenReturn(new RegisterOAuthClientResult(created, "raw-secret-shown-once"));

    mockMvc
        .perform(post(basePath()))
        .andExpect(status().isOk())
        .andExpect(
            model()
                .attribute(
                    "setupInstructions",
                    OidcClientSetupInstructions.from(
                        organizationId, "https://clavaris.example.test", created)));
  }

  @Test
  void plainDeactivatePostRedirectsToTheDetailPageOnSuccess() throws Exception {
    OAuthClient client = sampleClient();

    mockMvc
        .perform(post(basePath() + "/" + client.clientId() + "/deactivate"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath() + "/" + client.clientId()));

    verify(deactivateClient).handle(any());
  }

  @Test
  void htmxDeactivatePostReturnsTheDetailFragment() throws Exception {
    OAuthClient client = sampleClient().deactivate();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/deactivate").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW + " :: detail"));
  }

  // SDE-III review, 2026-09-15: ownership is enforced by DeactivateOAuthClientService itself (via
  // the organizationId this controller passes through), not a web-layer list-then-check —
  // simulated here the same way a real cross-tenant clientId would surface, via the exception the
  // service throws.
  @Test
  void deactivateReturnsNotFoundWhenTheClientBelongsToADifferentOrganization() throws Exception {
    doThrow(new OAuthClientNotFoundException("test_someone_elses"))
        .when(deactivateClient)
        .handle(any());

    mockMvc
        .perform(post(basePath() + "/test_someone_elses/deactivate"))
        .andExpect(status().isNotFound());
  }

  // Live UX request, 2026-09-24: reactivation — same shape as the deactivate tests above.
  @Test
  void plainActivatePostRedirectsToTheDetailPageOnSuccess() throws Exception {
    OAuthClient client = sampleClient();

    mockMvc
        .perform(post(basePath() + "/" + client.clientId() + "/activate"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath() + "/" + client.clientId()));

    verify(activateClient).handle(any());
  }

  @Test
  void htmxActivatePostReturnsTheDetailFragment() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/activate").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW + " :: detail"));
  }

  @Test
  void activateReturnsNotFoundWhenTheClientBelongsToADifferentOrganization() throws Exception {
    doThrow(
            new com.clavaris.clientregistry.application.usecase.activateoauthclient
                .OAuthClientNotFoundException("test_someone_elses"))
        .when(activateClient)
        .handle(any());

    mockMvc
        .perform(post(basePath() + "/test_someone_elses/activate"))
        .andExpect(status().isNotFound());
  }

  @Test
  void activateReturnsConflictWhenTheClientWasModifiedConcurrently() throws Exception {
    OAuthClient client = sampleClient();
    doThrow(new ConcurrentClientModificationException(client.clientId()))
        .when(activateClient)
        .handle(any());

    mockMvc
        .perform(post(basePath() + "/" + client.clientId() + "/activate"))
        .andExpect(status().isConflict());
  }

  @Test
  void plainRotateSecretPostRendersTheDetailPageDirectlyWithTheNewSecretNeverARedirect()
      throws Exception {
    OAuthClient client = sampleClient();
    when(rotateClientSecret.handle(any()))
        .thenReturn(new RotateOAuthClientSecretResult(client.clientId(), "new-raw-secret"));
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(post(basePath() + "/" + client.clientId() + "/rotate-secret"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW))
        .andExpect(model().attribute("justRegisteredRawSecret", "new-raw-secret"));
  }

  // Same rationale as deactivateReturnsNotFoundWhenTheClientBelongsToADifferentOrganization.
  @Test
  void rotateSecretReturnsNotFoundWhenTheClientBelongsToADifferentOrganization() throws Exception {
    doThrow(
            new com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret
                .OAuthClientNotFoundException("test_someone_elses"))
        .when(rotateClientSecret)
        .handle(any());

    mockMvc
        .perform(post(basePath() + "/test_someone_elses/rotate-secret"))
        .andExpect(status().isNotFound());
  }

  // SDE-III review, 2026-09-15: the web-layer half of the optimistic-locking fix — OAuthClient's
  // own @Version-backed conflict (proved directly against real Postgres in
  // JpaOAuthClientRepositoryTest) is the real, unconditional guarantee; these prove it surfaces
  // as a clean 409, not Spring MVC's default 500 for an unhandled ResponseStatusException-less
  // RuntimeException.
  @Test
  void deactivateReturnsConflictWhenTheClientWasModifiedConcurrently() throws Exception {
    OAuthClient client = sampleClient();
    doThrow(new ConcurrentClientModificationException(client.clientId()))
        .when(deactivateClient)
        .handle(any());

    mockMvc
        .perform(post(basePath() + "/" + client.clientId() + "/deactivate"))
        .andExpect(status().isConflict());
  }

  @Test
  void rotateSecretReturnsConflictWhenTheClientWasModifiedConcurrently() throws Exception {
    OAuthClient client = sampleClient();
    when(rotateClientSecret.handle(any()))
        .thenThrow(new ConcurrentClientModificationException(client.clientId()));

    mockMvc
        .perform(post(basePath() + "/" + client.clientId() + "/rotate-secret"))
        .andExpect(status().isConflict());
  }

  // BR-ORG-06: the one editable section — proves the form fields actually reach the use case.
  @Test
  void updateRedirectSettingsPassesTheParsedListsThroughToTheUseCase() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/redirect-settings")
                .param("redirectUris", "https://jobseeker.example.com/callback")
                .param("postLogoutRedirectUris", "https://jobseeker.example.com/logged-out"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath() + "/" + client.clientId()));

    verify(updateRedirectSettings).handle(any());
  }

  @Test
  void htmxUpdateRedirectSettingsReturnsTheDetailFragment() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/redirect-settings")
                .param("redirectUris", "https://example.com/callback")
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW + " :: detail"));

    verify(updateRedirectSettings).handle(any());
  }

  @Test
  void updateRedirectSettingsReturnsNotFoundWhenTheClientBelongsToADifferentOrganization()
      throws Exception {
    doThrow(
            new com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings
                .OAuthClientNotFoundException("test_someone_elses"))
        .when(updateRedirectSettings)
        .handle(any());

    mockMvc
        .perform(
            post(basePath() + "/test_someone_elses/redirect-settings")
                // A real redirect URI, so this genuinely reaches the use case below — the
                // at-least-one-required check added 2026-09-24 would otherwise 200/render an
                // error before ever calling it, and this test would stop proving what it claims
                // to.
                .param("redirectUris", "https://example.com/callback"))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateRedirectSettingsReturnsBadRequestForAMalformedRedirectUri() throws Exception {
    doThrow(new IllegalArgumentException("redirectUris must contain only well-formed URIs"))
        .when(updateRedirectSettings)
        .handle(any());

    mockMvc
        .perform(
            post(basePath() + "/test_abc/redirect-settings")
                .param("redirectUris", "not a uri at all ::"))
        .andExpect(status().isBadRequest());
  }

  // Live UX bug fix, 2026-09-24: an inactive client's redirect settings must not be editable.
  @Test
  void updateRedirectSettingsReturnsConflictWhenTheClientIsInactive() throws Exception {
    doThrow(new OAuthClientInactiveException("test_abc"))
        .when(updateRedirectSettings)
        .handle(any());

    mockMvc
        .perform(
            post(basePath() + "/test_abc/redirect-settings")
                .param("redirectUris", "https://jobseeker.example.com/callback"))
        .andExpect(status().isConflict());
  }

  // Live UX request, 2026-09-24: at least one real redirect URI is required to save — without
  // one, /authorize has nothing to match against for this client's own authorization_code grant
  // (BR-CLIENT-01). Web-layer-only (the domain itself still allows zero — the auto-provisioned
  // client's own genuine "not configured yet" state); confirmed by never calling the use case.
  @Test
  void updateRedirectSettingsRejectsAnEmptyRedirectUriListWithoutCallingTheUseCase()
      throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(post(basePath() + "/" + client.clientId() + "/redirect-settings"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW))
        .andExpect(
            content().string(containsString("At least one redirect URI is required to save.")));

    verifyNoInteractions(updateRedirectSettings);
  }

  @Test
  void updateRedirectSettingsRejectsAllBlankRedirectUriRowsTheSameAsTrulyEmpty() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/redirect-settings")
                .param("redirectUris[0]", "   "))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW));

    verifyNoInteractions(updateRedirectSettings);
  }

  // postLogoutRedirectUris carries no equivalent requirement — SAS's own bare default already
  // covers "not configured" for RP-Initiated Logout.
  @Test
  void updateRedirectSettingsSucceedsWithAnEmptyPostLogoutRedirectUriList() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/redirect-settings")
                .param("redirectUris", "https://example.com/callback"))
        .andExpect(status().is3xxRedirection());

    verify(updateRedirectSettings).handle(any());
  }

  // Live UX request, 2026-09-24 — real add/remove-row list UI, not a one-entry-per-line textarea.
  // These four endpoints never touch the domain (verifyNoInteractions(updateRedirectSettings)) —
  // only the form's own real "Save redirect settings" submit does.
  @Test
  void addRedirectUriRowAppendsABlankRowKeepingWhatWasAlreadyThereWithoutPersistingAnything()
      throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    MvcResult result =
        mockMvc
            .perform(
                post(basePath() + "/" + client.clientId() + "/redirect-settings/redirect-uris/add")
                    .param("redirectUris[0]", "https://jobseeker.example.com/callback"))
            .andExpect(status().isOk())
            .andExpect(view().name(DETAIL_VIEW))
            .andReturn();

    UpdateOAuthClientRedirectSettingsForm form =
        (UpdateOAuthClientRedirectSettingsForm)
            result.getModelAndView().getModel().get("redirectSettingsForm");
    assertThat(form.getRedirectUris())
        .containsExactly("https://jobseeker.example.com/callback", "");
    verifyNoInteractions(updateRedirectSettings);
  }

  @Test
  void removeRedirectUriRowRemovesOnlyTheGivenIndexKeepingTheOthersWithoutPersistingAnything()
      throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    MvcResult result =
        mockMvc
            .perform(
                post(basePath()
                        + "/"
                        + client.clientId()
                        + "/redirect-settings/redirect-uris/remove/1")
                    .param("redirectUris[0]", "https://jobseeker.example.com/callback")
                    .param("redirectUris[1]", "https://jobseeker.example.com/mobile-callback")
                    .param("redirectUris[2]", "https://jobseeker.example.com/staging-callback"))
            .andExpect(status().isOk())
            .andReturn();

    UpdateOAuthClientRedirectSettingsForm form =
        (UpdateOAuthClientRedirectSettingsForm)
            result.getModelAndView().getModel().get("redirectSettingsForm");
    assertThat(form.getRedirectUris())
        .containsExactly(
            "https://jobseeker.example.com/callback",
            "https://jobseeker.example.com/staging-callback");
    verifyNoInteractions(updateRedirectSettings);
  }

  // An owner removing the only row still has one blank input to type into — the same UX
  // UpdateOAuthClientRedirectSettingsForm#from already gives a freshly-loaded, zero-URI client.
  @Test
  void removeRedirectUriRowLeavesOneBlankRowWhenRemovingTheOnlyOne() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    MvcResult result =
        mockMvc
            .perform(
                post(basePath()
                        + "/"
                        + client.clientId()
                        + "/redirect-settings/redirect-uris/remove/0")
                    .param("redirectUris[0]", "https://jobseeker.example.com/callback"))
            .andExpect(status().isOk())
            .andReturn();

    UpdateOAuthClientRedirectSettingsForm form =
        (UpdateOAuthClientRedirectSettingsForm)
            result.getModelAndView().getModel().get("redirectSettingsForm");
    assertThat(form.getRedirectUris()).containsExactly("");
  }

  @Test
  void addPostLogoutRedirectUriRowAppendsABlankRowWithoutPersistingAnything() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    MvcResult result =
        mockMvc
            .perform(
                post(basePath()
                        + "/"
                        + client.clientId()
                        + "/redirect-settings/post-logout-redirect-uris/add")
                    .param("postLogoutRedirectUris[0]", "https://jobseeker.example.com/logged-out"))
            .andExpect(status().isOk())
            .andReturn();

    UpdateOAuthClientRedirectSettingsForm form =
        (UpdateOAuthClientRedirectSettingsForm)
            result.getModelAndView().getModel().get("redirectSettingsForm");
    assertThat(form.getPostLogoutRedirectUris())
        .containsExactly("https://jobseeker.example.com/logged-out", "");
    verifyNoInteractions(updateRedirectSettings);
  }

  @Test
  void removePostLogoutRedirectUriRowRemovesOnlyTheGivenIndexWithoutPersistingAnything()
      throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    MvcResult result =
        mockMvc
            .perform(
                post(basePath()
                        + "/"
                        + client.clientId()
                        + "/redirect-settings/post-logout-redirect-uris/remove/0")
                    .param("postLogoutRedirectUris[0]", "https://jobseeker.example.com/logged-out")
                    .param(
                        "postLogoutRedirectUris[1]", "https://jobseeker.example.com/marketing-bye"))
            .andExpect(status().isOk())
            .andReturn();

    UpdateOAuthClientRedirectSettingsForm form =
        (UpdateOAuthClientRedirectSettingsForm)
            result.getModelAndView().getModel().get("redirectSettingsForm");
    assertThat(form.getPostLogoutRedirectUris())
        .containsExactly("https://jobseeker.example.com/marketing-bye");
    verifyNoInteractions(updateRedirectSettings);
  }

  @Test
  void htmxAddRedirectUriRowReturnsTheDetailFragment() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/redirect-settings/redirect-uris/add")
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW + " :: detail"));
  }

  // Content-level proof the list page no longer offers a create form at all — a regression this
  // module's own pmd:cpd-check can't catch, unlike the removed RegisterOAuthClientForm class
  // itself.
  @Test
  void listPageNoLongerRendersACreateForm() throws Exception {
    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Register a new OAuth Client"))));
  }

  // Live UX request, 2026-09-24: the Configuration card became editable — same test shapes as
  // the equivalent updateRedirectSettings/deactivate tests above.
  @Test
  void plainUpdateGrantTypesPostRedirectsToTheDetailPageOnSuccess() throws Exception {
    OAuthClient client = sampleClient();

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/grant-types")
                .param("allowedGrantTypes", "authorization_code", "refresh_token"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath() + "/" + client.clientId()));

    verify(updateGrantTypes).handle(any());
  }

  @Test
  void htmxUpdateGrantTypesReturnsTheDetailFragment() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/grant-types")
                .param("allowedGrantTypes", "client_credentials")
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW + " :: detail"));
  }

  // An owner unchecking every box submits no allowedGrantTypes param at all — must not NPE.
  @Test
  void updateGrantTypesWithNoCheckboxesCheckedSubmitsAnEmptyList() throws Exception {
    OAuthClient client = sampleClient();

    mockMvc
        .perform(post(basePath() + "/" + client.clientId() + "/grant-types"))
        .andExpect(status().is3xxRedirection());

    verify(updateGrantTypes).handle(any());
  }

  @Test
  void updateGrantTypesReturnsNotFoundWhenTheClientBelongsToADifferentOrganization()
      throws Exception {
    doThrow(
            new com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes
                .OAuthClientNotFoundException("test_someone_elses"))
        .when(updateGrantTypes)
        .handle(any());

    mockMvc
        .perform(post(basePath() + "/test_someone_elses/grant-types"))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateGrantTypesReturnsConflictWhenTheClientIsInactive() throws Exception {
    doThrow(
            new com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes
                .OAuthClientInactiveException("test_abc"))
        .when(updateGrantTypes)
        .handle(any());

    mockMvc.perform(post(basePath() + "/test_abc/grant-types")).andExpect(status().isConflict());
  }

  @Test
  void plainUpdateScopesPostRedirectsToTheDetailPageOnSuccess() throws Exception {
    OAuthClient client = sampleClient();

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/scopes")
                .param("allowedScopes", "openid", "test.read"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath() + "/" + client.clientId()));

    verify(updateScopes).handle(any());
  }

  @Test
  void htmxUpdateScopesReturnsTheDetailFragment() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/scopes")
                .param("allowedScopes", "openid")
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW + " :: detail"));
  }

  // Same blank-row tolerance the redirect-URI add/remove-row UI already gives — the modal's own
  // "+ Add scope" JS can leave an unfilled row behind.
  @Test
  void updateScopesDropsBlankEntriesBeforeCallingTheUseCase() throws Exception {
    OAuthClient client = sampleClient();
    ArgumentCaptor<
            com.clavaris.clientregistry.application.usecase.updateoauthclientscopes
                .UpdateOAuthClientScopesCommand>
        captor =
            ArgumentCaptor.forClass(
                com.clavaris.clientregistry.application.usecase.updateoauthclientscopes
                    .UpdateOAuthClientScopesCommand.class);

    mockMvc.perform(
        post(basePath() + "/" + client.clientId() + "/scopes")
            .param("allowedScopes", "openid", "", "  "));

    verify(updateScopes).handle(captor.capture());
    assertThat(captor.getValue().allowedScopes()).containsExactly("openid");
  }

  @Test
  void updateScopesReturnsConflictWhenTheClientIsInactive() throws Exception {
    doThrow(
            new com.clavaris.clientregistry.application.usecase.updateoauthclientscopes
                .OAuthClientInactiveException("test_abc"))
        .when(updateScopes)
        .handle(any());

    mockMvc.perform(post(basePath() + "/test_abc/scopes")).andExpect(status().isConflict());
  }

  @Test
  void plainUpdateConsentPostRedirectsToTheDetailPageOnSuccess() throws Exception {
    OAuthClient client = sampleClient();

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/consent").param("requireConsent", "true"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath() + "/" + client.clientId()));

    verify(updateConsent).handle(any());
  }

  @Test
  void htmxUpdateConsentReturnsTheDetailFragment() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(
            post(basePath() + "/" + client.clientId() + "/consent").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(DETAIL_VIEW + " :: detail"));
  }

  // An unchecked toggle submits no requireConsent param at all — must resolve to false, not NPE.
  @Test
  void updateConsentWithTheToggleUncheckedSubmitsFalse() throws Exception {
    OAuthClient client = sampleClient();
    ArgumentCaptor<
            com.clavaris.clientregistry.application.usecase.updateoauthclientconsent
                .UpdateOAuthClientConsentCommand>
        captor =
            ArgumentCaptor.forClass(
                com.clavaris.clientregistry.application.usecase.updateoauthclientconsent
                    .UpdateOAuthClientConsentCommand.class);

    mockMvc.perform(post(basePath() + "/" + client.clientId() + "/consent"));

    verify(updateConsent).handle(captor.capture());
    assertThat(captor.getValue().requireConsent()).isFalse();
  }

  @Test
  void updateConsentReturnsConflictWhenTheClientIsInactive() throws Exception {
    doThrow(
            new com.clavaris.clientregistry.application.usecase.updateoauthclientconsent
                .OAuthClientInactiveException("test_abc"))
        .when(updateConsent)
        .handle(any());

    mockMvc.perform(post(basePath() + "/test_abc/consent")).andExpect(status().isConflict());
  }

  @Test
  void configurationCardShowsEditButtonsAndDialogsOnlyForAnActiveClient() throws Exception {
    OAuthClient client = sampleClient();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(get(basePath() + "/" + client.clientId()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("grant-types-dialog")))
        .andExpect(content().string(containsString("scopes-dialog")))
        .andExpect(content().string(containsString("consent-dialog")));
  }

  @Test
  void configurationCardHidesEditButtonsForAnInactiveClientAndShowsAnExplanation()
      throws Exception {
    OAuthClient client = sampleClient().deactivate();
    when(getClient.handle(any())).thenReturn(Optional.of(client));

    mockMvc
        .perform(get(basePath() + "/" + client.clientId()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("configuration can't be edited until you")))
        .andExpect(
            content().string(not(containsString("data-dialog-open=\"grant-types-dialog\""))));
  }
}

package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationNotFoundException;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientUseCase;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.clientregistry.domain.model.PkcePolicy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc setup — same pattern/rationale as organization-module's
 * CreateOrganizationControllerTest ({@code @WebMvcTest} doesn't exist in this Spring Boot version).
 */
class RegisterOAuthClientControllerTest {

  // Same standalone-MockMvc "resolve Authentication via .principal(...)" pattern as
  // SetRedirectPolicyControllerTest's own identical constant.
  private static final TestingAuthenticationToken ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private final UUID organizationId = UUID.randomUUID();
  private RegisterOAuthClientUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(RegisterOAuthClientUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new RegisterOAuthClientController(useCase)).build();
  }

  @Test
  void returns201WithTheGeneratedClientIdAndSecret() throws Exception {
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "a-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    when(useCase.handle(any())).thenReturn(new RegisterOAuthClientResult(client, "the-raw-secret"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"redirectUris\":[\"https://jobseeker.example.com/callback\"],"
                        + "\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[\"openid\"]}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.clientId").value("a-client-id"))
        .andExpect(jsonPath("$.clientSecret").value("the-raw-secret"))
        .andExpect(jsonPath("$.organizationId").value(organizationId.toString()));
  }

  @Test
  void omittingRequireConsentInTheRequestBodyResolvesToTheSecureDefault() throws Exception {
    // TD-SEC-026/ADR-0017: the real, load-bearing behavior — this test would still pass with a
    // stub useCase even if the controller silently dropped the field, so it asserts on the
    // COMMAND the controller actually built, not just the response the mocked use case echoes
    // back.
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "a-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    when(useCase.handle(any())).thenReturn(new RegisterOAuthClientResult(client, "the-raw-secret"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"redirectUris\":[\"https://jobseeker.example.com/callback\"],"
                        + "\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[\"openid\"]}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isCreated());

    ArgumentCaptor<RegisterOAuthClientCommand> captor =
        ArgumentCaptor.forClass(RegisterOAuthClientCommand.class);
    verify(useCase).handle(captor.capture());
    assertThat(captor.getValue().requireConsent()).isTrue();
  }

  @Test
  void anExplicitFalseRequireConsentReachesTheCommandUnchanged() throws Exception {
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "a-trusted-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            false,
            List.of());
    when(useCase.handle(any())).thenReturn(new RegisterOAuthClientResult(client, "the-raw-secret"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"redirectUris\":[\"https://jobseeker.example.com/callback\"],"
                        + "\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[\"openid\"],"
                        + "\"requireConsent\":false}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.requireConsent").value(false));

    ArgumentCaptor<RegisterOAuthClientCommand> captor =
        ArgumentCaptor.forClass(RegisterOAuthClientCommand.class);
    verify(useCase).handle(captor.capture());
    assertThat(captor.getValue().requireConsent()).isFalse();
  }

  @Test
  void rejectsEmptyRedirectUrisWithoutEverCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"redirectUris\":[],\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[]}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isBadRequest());
  }

  @Test
  void returns404WhenTheOrganizationDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenThrow(new OrganizationNotFoundException(organizationId));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"redirectUris\":[\"https://jobseeker.example.com/callback\"],"
                        + "\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[]}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }

  // TD-FUT-018: same "assert on the command the controller actually built" bar as
  // omittingRequireConsentInTheRequestBodyResolvesToTheSecureDefault above.
  @Test
  void omittingPostLogoutRedirectUrisInTheRequestBodyResolvesToAnEmptyList() throws Exception {
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "a-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    when(useCase.handle(any())).thenReturn(new RegisterOAuthClientResult(client, "the-raw-secret"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"redirectUris\":[\"https://jobseeker.example.com/callback\"],"
                        + "\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[\"openid\"]}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isCreated());

    ArgumentCaptor<RegisterOAuthClientCommand> captor =
        ArgumentCaptor.forClass(RegisterOAuthClientCommand.class);
    verify(useCase).handle(captor.capture());
    assertThat(captor.getValue().postLogoutRedirectUris()).isEmpty();
  }

  @Test
  void anExplicitPostLogoutRedirectUriReachesTheCommandUnchanged() throws Exception {
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "a-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of("https://jobseeker.example.com/logged-out"));
    when(useCase.handle(any())).thenReturn(new RegisterOAuthClientResult(client, "the-raw-secret"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"redirectUris\":[\"https://jobseeker.example.com/callback\"],"
                        + "\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[\"openid\"],"
                        + "\"postLogoutRedirectUris\":[\"https://jobseeker.example.com/logged-out\"]}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isCreated())
        .andExpect(
            jsonPath("$.postLogoutRedirectUris[0]")
                .value("https://jobseeker.example.com/logged-out"));

    ArgumentCaptor<RegisterOAuthClientCommand> captor =
        ArgumentCaptor.forClass(RegisterOAuthClientCommand.class);
    verify(useCase).handle(captor.capture());
    assertThat(captor.getValue().postLogoutRedirectUris())
        .containsExactly("https://jobseeker.example.com/logged-out");
  }

  // ADR-0043: the per-client PKCE policy at the registration boundary.

  private static final String BODY_START =
      "{\"redirectUris\":[\"https://jobseeker.example.com/callback\"],"
          + "\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[\"openid\"]";

  private OAuthClient clientWithPolicy(final PkcePolicy policy) {
    return OAuthClient.register(
        organizationId,
        "a-client-id",
        "argon2id$hashed",
        List.of("https://jobseeker.example.com/callback"),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        policy,
        List.of());
  }

  @Test
  void omittingPkcePolicyResolvesToTheStrictDefaultInTheCommand() throws Exception {
    when(useCase.handle(any()))
        .thenReturn(new RegisterOAuthClientResult(clientWithPolicy(PkcePolicy.REQUIRED), "s"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY_START + "}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.pkcePolicy").value("REQUIRED"));

    ArgumentCaptor<RegisterOAuthClientCommand> captor =
        ArgumentCaptor.forClass(RegisterOAuthClientCommand.class);
    verify(useCase).handle(captor.capture());
    assertThat(captor.getValue().pkcePolicy()).isEqualTo(PkcePolicy.REQUIRED);
  }

  @Test
  void anExplicitOptionalPkcePolicyReachesTheCommandAndIsEchoedBack() throws Exception {
    when(useCase.handle(any()))
        .thenReturn(new RegisterOAuthClientResult(clientWithPolicy(PkcePolicy.OPTIONAL), "s"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY_START + ",\"pkcePolicy\":\"OPTIONAL\"}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.pkcePolicy").value("OPTIONAL"));

    ArgumentCaptor<RegisterOAuthClientCommand> captor =
        ArgumentCaptor.forClass(RegisterOAuthClientCommand.class);
    verify(useCase).handle(captor.capture());
    assertThat(captor.getValue().pkcePolicy()).isEqualTo(PkcePolicy.OPTIONAL);
  }

  @Test
  void aPkcePolicyThatIsNeitherValueIsABadRequestAndNeverReachesTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY_START + ",\"pkcePolicy\":\"NONE\"}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isBadRequest());

    verify(useCase, never()).handle(any());
  }

  // TD-SEC-104 / ADR-0049: an omitted lifetime is the default, an explicit one reaches the command
  // unchanged, and one outside the cap is a 400 that reaches no use case.
  @Test
  void anExplicitAccessTokenLifetimeReachesTheCommandAndTheResponseShowsTheEffectiveOne()
      throws Exception {
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "a-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            com.clavaris.clientregistry.domain.model.PkcePolicy.REQUIRED,
            List.of(),
            120);
    when(useCase.handle(any())).thenReturn(new RegisterOAuthClientResult(client, "the-raw-secret"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"redirectUris\":[\"https://jobseeker.example.com/callback\"],"
                        + "\"allowedGrantTypes\":[\"authorization_code\"],"
                        + "\"allowedScopes\":[\"openid\"],\"accessTokenTtlSeconds\":120}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isCreated())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.accessTokenTtlSeconds")
                .value(120));

    ArgumentCaptor<RegisterOAuthClientCommand> captor =
        ArgumentCaptor.forClass(RegisterOAuthClientCommand.class);
    verify(useCase).handle(captor.capture());
    assertThat(captor.getValue().accessTokenTtlSeconds()).isEqualTo(120);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {-1, 0, 59, 3601, 86400})
  void anAccessTokenLifetimeOutsideTheCapIsABadRequestAndReachesNoUseCase(final int seconds)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"redirectUris\":[\"https://jobseeker.example.com/callback\"],"
                        + "\"allowedGrantTypes\":[\"authorization_code\"],"
                        + "\"allowedScopes\":[\"openid\"],\"accessTokenTtlSeconds\":"
                        + seconds
                        + "}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isBadRequest());

    org.mockito.Mockito.verifyNoInteractions(useCase);
  }
}

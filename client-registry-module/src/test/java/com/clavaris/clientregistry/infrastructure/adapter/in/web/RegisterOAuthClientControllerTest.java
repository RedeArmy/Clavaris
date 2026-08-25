package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc setup — same pattern/rationale as organization-module's
 * CreateOrganizationControllerTest ({@code @WebMvcTest} doesn't exist in this Spring Boot version).
 */
class RegisterOAuthClientControllerTest {

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
            true);
    when(useCase.handle(any())).thenReturn(new RegisterOAuthClientResult(client, "the-raw-secret"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"redirectUris\":[\"https://jobseeker.example.com/callback\"],"
                        + "\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[\"openid\"]}"))
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
            true);
    when(useCase.handle(any())).thenReturn(new RegisterOAuthClientResult(client, "the-raw-secret"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"redirectUris\":[\"https://jobseeker.example.com/callback\"],"
                        + "\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[\"openid\"]}"))
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
            false);
    when(useCase.handle(any())).thenReturn(new RegisterOAuthClientResult(client, "the-raw-secret"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"redirectUris\":[\"https://jobseeker.example.com/callback\"],"
                        + "\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[\"openid\"],"
                        + "\"requireConsent\":false}"))
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
                    "{\"redirectUris\":[],\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[]}"))
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
                        + "\"allowedGrantTypes\":[\"authorization_code\"],\"allowedScopes\":[]}"))
        .andExpect(status().isNotFound());
  }
}

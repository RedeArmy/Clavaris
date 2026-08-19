package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegisterOAuthClientServiceTest {

  private final UUID organizationId = UUID.randomUUID();
  private OAuthClientRepository oauthClients;
  private OrganizationExistsChecker organizationExistsChecker;
  private ClientSecretHasher hasher;
  private RegisterOAuthClientService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    organizationExistsChecker = mock(OrganizationExistsChecker.class);
    hasher = mock(ClientSecretHasher.class);
    service = new RegisterOAuthClientService(oauthClients, organizationExistsChecker, hasher);

    when(organizationExistsChecker.exists(organizationId)).thenReturn(true);
    when(hasher.hash(anyString())).thenReturn("argon2id$hashed");
  }

  @Test
  void registersAndPersistsANewClientWithAServerGeneratedIdAndSecret() {
    RegisterOAuthClientResult result =
        service.handle(
            new RegisterOAuthClientCommand(
                organizationId,
                List.of("https://jobseeker.example.com/callback"),
                List.of("authorization_code"),
                List.of("openid")));

    assertThat(result.client().organizationId()).isEqualTo(organizationId);
    assertThat(result.client().clientId()).isNotBlank();
    assertThat(result.client().clientSecretHash()).isEqualTo("argon2id$hashed");
    assertThat(result.rawClientSecret()).isNotBlank();
    verify(oauthClients).save(result.client());
  }

  @Test
  void neverHashesOrPersistsTheRawSecretItself() {
    RegisterOAuthClientResult result =
        service.handle(
            new RegisterOAuthClientCommand(
                organizationId,
                List.of("https://jobseeker.example.com/callback"),
                List.of("authorization_code"),
                List.of("openid")));

    // The stored hash must never equal the raw secret handed back to the caller — that would mean
    // the "hasher" silently did nothing.
    assertThat(result.client().clientSecretHash()).isNotEqualTo(result.rawClientSecret());
  }

  @Test
  void generatesADifferentClientIdAndSecretOnEachCall() {
    RegisterOAuthClientCommand command =
        new RegisterOAuthClientCommand(
            organizationId,
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"));

    RegisterOAuthClientResult first = service.handle(command);
    RegisterOAuthClientResult second = service.handle(command);

    assertThat(first.client().clientId()).isNotEqualTo(second.client().clientId());
    assertThat(first.rawClientSecret()).isNotEqualTo(second.rawClientSecret());
  }

  @Test
  void rejectsRegistrationUnderANonExistentOrganization() {
    UUID unknownOrganizationId = UUID.randomUUID();
    when(organizationExistsChecker.exists(unknownOrganizationId)).thenReturn(false);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(
            () ->
                service.handle(
                    new RegisterOAuthClientCommand(
                        unknownOrganizationId,
                        List.of("https://jobseeker.example.com/callback"),
                        List.of("authorization_code"),
                        List.of("openid"))));

    verify(oauthClients, never()).save(any());
  }
}

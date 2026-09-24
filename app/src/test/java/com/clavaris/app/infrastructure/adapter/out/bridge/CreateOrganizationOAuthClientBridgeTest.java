package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientDefaults;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientUseCase;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createorganization.OAuthClientProvisioner;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * BR-ORG-06: proves the bridge actually wires organization-module's port to client-registry-
 * module's real registration use case with {@link OAuthClientDefaults}' fixed values — mocked at
 * the port boundary on both sides, same shape as {@code CreateOrganizationSigningKeyBridgeTest}.
 */
class CreateOrganizationOAuthClientBridgeTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final RegisterOAuthClientUseCase registerOAuthClient =
      mock(RegisterOAuthClientUseCase.class);
  private final CreateOrganizationOAuthClientBridge bridge =
      new CreateOrganizationOAuthClientBridge(registerOAuthClient);

  @Test
  void registersWithOAuthClientDefaultsAndNoRedirectUrisYet() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "test_a-client-id",
            "argon2id$hashed",
            List.of(),
            OAuthClientDefaults.GRANT_TYPES,
            OAuthClientDefaults.SCOPES,
            OAuthClientDefaults.REQUIRE_CONSENT,
            List.of());
    when(registerOAuthClient.handle(any()))
        .thenReturn(new RegisterOAuthClientResult(client, "raw-secret"));

    OAuthClientProvisioner.ProvisionedOAuthClient result =
        bridge.provisionFor(organizationId, ACTOR);

    assertThat(result.id()).isEqualTo(client.id());
    assertThat(result.clientId()).isEqualTo("test_a-client-id");

    ArgumentCaptor<RegisterOAuthClientCommand> captor =
        ArgumentCaptor.forClass(RegisterOAuthClientCommand.class);
    verify(registerOAuthClient).handle(captor.capture());
    RegisterOAuthClientCommand command = captor.getValue();
    assertThat(command.organizationId()).isEqualTo(organizationId);
    assertThat(command.redirectUris()).isEmpty();
    assertThat(command.postLogoutRedirectUris()).isEmpty();
    assertThat(command.allowedGrantTypes()).isEqualTo(OAuthClientDefaults.GRANT_TYPES);
    assertThat(command.allowedScopes()).isEqualTo(OAuthClientDefaults.SCOPES);
    assertThat(command.requireConsent()).isEqualTo(OAuthClientDefaults.REQUIRE_CONSENT);
    assertThat(command.actor()).isEqualTo(ACTOR);
  }
}

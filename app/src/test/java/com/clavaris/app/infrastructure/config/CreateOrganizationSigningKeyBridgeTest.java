package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.ActivateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * BR-ORG-06: proves the bridge actually wires organization-module's port to identity-module's real
 * key machinery — mocked at the port boundary on both sides, since a full round trip against real
 * persistence is {@code app}'s own {@code CreateOrganizationIntegrationTest} (platform-tier
 * composition PR), not this class's job.
 */
class CreateOrganizationSigningKeyBridgeTest {

  private final ActivateSigningKeyForOrganizationUseCase keyActivator =
      mock(ActivateSigningKeyForOrganizationUseCase.class);
  private final OrganizationSigningKeyMaterialFactory materialFactory =
      mock(OrganizationSigningKeyMaterialFactory.class);
  private final CreateOrganizationSigningKeyBridge bridge =
      new CreateOrganizationSigningKeyBridge(keyActivator, materialFactory);

  @Test
  void generatesMaterialBeforeActivatingItAndReturnsTheProvisionedKey() {
    UUID organizationId = UUID.randomUUID();
    when(materialFactory.generateFor(any())).thenReturn("a-kid");
    SigningKey activated =
        SigningKey.activate(new OrganizationId(organizationId), "a-kid", "RS256");
    when(keyActivator.handle(any(), eq("a-kid"), eq("RS256"))).thenReturn(activated);

    SigningKeyProvisioner.ProvisionedSigningKey result = bridge.provisionFor(organizationId);

    assertThat(result.id()).isEqualTo(activated.id());
    assertThat(result.kid()).isEqualTo("a-kid");
    assertThat(result.algorithm()).isEqualTo("RS256");
  }

  @Test
  void passesTheExactOrganizationIdThroughToBothCollaborators() {
    UUID organizationId = UUID.randomUUID();
    OrganizationId expected = new OrganizationId(organizationId);
    when(materialFactory.generateFor(any())).thenReturn("a-kid");
    when(keyActivator.handle(any(), any(), any()))
        .thenReturn(SigningKey.activate(expected, "a-kid", "RS256"));

    bridge.provisionFor(organizationId);

    verify(materialFactory).generateFor(expected);
    verify(keyActivator).handle(expected, "a-kid", "RS256");
  }
}

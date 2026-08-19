package com.clavaris.organization.application.usecase.createorganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner.ProvisionedSigningKey;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateOrganizationServiceTest {

  private OrganizationRepository organizations;
  private SigningKeyProvisioner keyProvisioner;
  private CreateOrganizationService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    keyProvisioner = mock(SigningKeyProvisioner.class);
    service = new CreateOrganizationService(organizations, keyProvisioner);
  }

  @Test
  void createsAndPersistsTheOrganization() {
    when(keyProvisioner.provisionFor(any()))
        .thenReturn(new ProvisionedSigningKey(UUID.randomUUID(), "a-kid", "RS256"));

    final CreateOrganizationResult result =
        service.handle(new CreateOrganizationCommand("JobSeeker"));

    assertThat(result.organization().name()).isEqualTo("JobSeeker");
    verify(organizations).save(result.organization());
  }

  @Test
  void provisionsTheInitialSigningKeySynchronouslyInTheSameOperation() {
    // BR-ORG-06: an Organization that exists but cannot yet issue a token is never an observable
    // state — the signing key must be provisioned for the exact Organization just created, not a
    // stale or mismatched id.
    when(keyProvisioner.provisionFor(any()))
        .thenReturn(new ProvisionedSigningKey(UUID.randomUUID(), "a-kid", "RS256"));

    final CreateOrganizationResult result =
        service.handle(new CreateOrganizationCommand("JobSeeker"));

    verify(keyProvisioner).provisionFor(result.organization().id());
    assertThat(result.signingKey().kid()).isEqualTo("a-kid");
    assertThat(result.signingKey().algorithm()).isEqualTo("RS256");
  }
}

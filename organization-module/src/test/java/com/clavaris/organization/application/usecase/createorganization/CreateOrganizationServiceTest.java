package com.clavaris.organization.application.usecase.createorganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner.ProvisionedSigningKey;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateOrganizationServiceTest {

  private OrganizationRepository organizations;
  private SigningKeyProvisioner keyProvisioner;
  private PlatformAccountExistsChecker platformAccountExistsChecker;
  private CreateOrganizationService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    keyProvisioner = mock(SigningKeyProvisioner.class);
    platformAccountExistsChecker = mock(PlatformAccountExistsChecker.class);
    when(platformAccountExistsChecker.exists(any())).thenReturn(true);
    service =
        new CreateOrganizationService(organizations, keyProvisioner, platformAccountExistsChecker);
  }

  @Test
  void createsAndPersistsTheOrganization() {
    when(keyProvisioner.provisionFor(any()))
        .thenReturn(new ProvisionedSigningKey(UUID.randomUUID(), "a-kid", "RS256"));

    final CreateOrganizationResult result =
        service.handle(new CreateOrganizationCommand("JobSeeker", UUID.randomUUID()));

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
        service.handle(new CreateOrganizationCommand("JobSeeker", UUID.randomUUID()));

    verify(keyProvisioner).provisionFor(result.organization().id());
    assertThat(result.signingKey().kid()).isEqualTo("a-kid");
    assertThat(result.signingKey().algorithm()).isEqualTo("RS256");
  }

  // Security finding (SDE-III review, 2026-08-22), regression test for its fix: before this fix,
  // ownerPlatformAccountId was never checked against a real PlatformAccount anywhere in this path.
  @Test
  void rejectsAnOwnerPlatformAccountIdThatDoesNotExistWithoutPersistingAnything() {
    UUID nonExistentPlatformAccountId = UUID.randomUUID();
    when(platformAccountExistsChecker.exists(nonExistentPlatformAccountId)).thenReturn(false);
    CreateOrganizationCommand command =
        new CreateOrganizationCommand("Ghost Owner Co", nonExistentPlatformAccountId);

    assertThatExceptionOfType(PlatformAccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizations, never()).save(any());
    verifyNoInteractions(keyProvisioner);
  }
}

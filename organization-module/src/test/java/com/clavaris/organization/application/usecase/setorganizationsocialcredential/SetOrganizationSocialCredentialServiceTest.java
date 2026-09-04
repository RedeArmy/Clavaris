package com.clavaris.organization.application.usecase.setorganizationsocialcredential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetOrganizationSocialCredentialServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private OrganizationRepository organizations;
  private OrganizationSocialCredentialRepository credentials;
  private OrganizationSocialCredentialCipher cipher;
  private AuditEventRecorder auditEvents;
  private SetOrganizationSocialCredentialService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    credentials = mock(OrganizationSocialCredentialRepository.class);
    cipher = mock(OrganizationSocialCredentialCipher.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new SetOrganizationSocialCredentialService(organizations, credentials, cipher, auditEvents);
  }

  @Test
  void definesAFreshCredentialWhenNoneExistsYetForAProductionOrganizationThatAllowsTheProvider() {
    UUID organizationId = UUID.randomUUID();
    Organization production =
        Organization.registerProductionEnvironment("Acme", UUID.randomUUID(), UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE"));
    when(organizations.findById(organizationId))
        .thenReturn(Optional.of(withId(production, organizationId)));
    when(credentials.findByOrganizationIdAndProvider(organizationId, SocialProvider.GOOGLE))
        .thenReturn(Optional.empty());
    when(cipher.encrypt("raw-secret")).thenReturn("encrypted-secret");

    SetOrganizationSocialCredentialResult result =
        service.handle(
            new SetOrganizationSocialCredentialCommand(
                organizationId, SocialProvider.GOOGLE, "client-id", "raw-secret", ACTOR));

    assertThat(result.credential().clientId()).isEqualTo("client-id");
    assertThat(result.credential().clientSecretEncrypted()).isEqualTo("encrypted-secret");
    verify(credentials).save(result.credential());
    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("organization.social_credential_set"),
            eq("Organization"),
            eq(organizationId.toString()),
            any());
  }

  @Test
  void rejectsANonExistentOrganization() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.findById(organizationId)).thenReturn(Optional.empty());
    SetOrganizationSocialCredentialCommand command =
        new SetOrganizationSocialCredentialCommand(
            organizationId, SocialProvider.GOOGLE, "client-id", "raw-secret", ACTOR);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(credentials, never()).save(any());
    verifyNoInteractions(auditEvents, cipher);
  }

  @Test
  void rejectsADevelopmentOrganizationEvenIfSocialLoginIsAllowed() {
    UUID organizationId = UUID.randomUUID();
    Organization development =
        Organization.register("Acme", UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE"));
    when(organizations.findById(organizationId))
        .thenReturn(Optional.of(withId(development, organizationId)));
    SetOrganizationSocialCredentialCommand command =
        new SetOrganizationSocialCredentialCommand(
            organizationId, SocialProvider.GOOGLE, "client-id", "raw-secret", ACTOR);

    assertThatExceptionOfType(OrganizationNotProductionException.class)
        .isThrownBy(() -> service.handle(command));

    verify(credentials, never()).save(any());
    verifyNoInteractions(auditEvents, cipher);
  }

  @Test
  void rejectsAProviderSocialLoginHasNotBeenEnabledFor() {
    UUID organizationId = UUID.randomUUID();
    Organization production =
        Organization.registerProductionEnvironment("Acme", UUID.randomUUID(), UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GITHUB"));
    when(organizations.findById(organizationId))
        .thenReturn(Optional.of(withId(production, organizationId)));
    SetOrganizationSocialCredentialCommand command =
        new SetOrganizationSocialCredentialCommand(
            organizationId, SocialProvider.GOOGLE, "client-id", "raw-secret", ACTOR);

    assertThatExceptionOfType(SocialLoginNotEnabledForProviderException.class)
        .isThrownBy(() -> service.handle(command));

    verify(credentials, never()).save(any());
    verifyNoInteractions(auditEvents, cipher);
  }

  @Test
  void neverPersistsTheRawSecretOnlyTheEncryptedForm() {
    UUID organizationId = UUID.randomUUID();
    Organization production =
        Organization.registerProductionEnvironment("Acme", UUID.randomUUID(), UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE"));
    when(organizations.findById(organizationId))
        .thenReturn(Optional.of(withId(production, organizationId)));
    when(credentials.findByOrganizationIdAndProvider(organizationId, SocialProvider.GOOGLE))
        .thenReturn(Optional.empty());
    when(cipher.encrypt("raw-secret")).thenReturn("encrypted-secret");

    service.handle(
        new SetOrganizationSocialCredentialCommand(
            organizationId, SocialProvider.GOOGLE, "client-id", "raw-secret", ACTOR));

    verify(cipher).encrypt("raw-secret");
    verify(credentials)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                saved ->
                    saved.clientSecretEncrypted().equals("encrypted-secret")
                        && !saved.clientSecretEncrypted().equals("raw-secret")));
  }

  // Organization.register()/registerProductionEnvironment() always assign a fresh random id — this
  // test suite needs the mock's own organizationId key to match exactly, so reconstitute a copy
  // under the id the test controls, same trick OrganizationTest's own fixtures already use.
  private static Organization withId(final Organization source, final UUID organizationId) {
    return Organization.reconstitute(
        organizationId,
        source.name(),
        source.createdAt(),
        source.ownerPlatformAccountId(),
        source.socialLoginEnabled(),
        source.allowedSocialProviders(),
        source.environment(),
        source.linkedEnvironmentOrganizationId().orElse(null));
  }
}

package com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetSocialLoginPolicyForOrganizationServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private OrganizationRepository organizations;
  private AuditEventRecorder auditEvents;
  private SetSocialLoginPolicyForOrganizationService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new SetSocialLoginPolicyForOrganizationService(organizations, auditEvents);
  }

  @Test
  void enablesSocialLoginWithTheGivenProviders() {
    UUID organizationId = UUID.randomUUID();
    Organization existing = Organization.register("Acme", UUID.randomUUID());
    when(organizations.findById(organizationId)).thenReturn(Optional.of(existing));

    SetSocialLoginPolicyForOrganizationResult result =
        service.handle(
            new SetSocialLoginPolicyForOrganizationCommand(
                organizationId, true, List.of("GOOGLE", "GITHUB"), ACTOR));

    assertThat(result.organization().socialLoginEnabled()).isTrue();
    assertThat(result.organization().allowedSocialProviders()).containsExactly("GOOGLE", "GITHUB");
    verify(organizations).save(result.organization());
  }

  @Test
  void disablingNeverTouchesEmailPasswordAvailability() {
    // This test's own point is what it does NOT assert — there is no email/password-related field
    // or port call anywhere in this service, by design (ADR-0020 Decision 3): disabling social
    // login structurally cannot reach the code path that governs email/password at all.
    UUID organizationId = UUID.randomUUID();
    Organization existing = Organization.register("Acme", UUID.randomUUID());
    when(organizations.findById(organizationId)).thenReturn(Optional.of(existing));

    SetSocialLoginPolicyForOrganizationResult result =
        service.handle(
            new SetSocialLoginPolicyForOrganizationCommand(
                organizationId, false, List.of(), ACTOR));

    assertThat(result.organization().socialLoginEnabled()).isFalse();
  }

  @Test
  void recordsAnAuditEventForTheChange() {
    UUID organizationId = UUID.randomUUID();
    Organization existing = Organization.register("Acme", UUID.randomUUID());
    when(organizations.findById(organizationId)).thenReturn(Optional.of(existing));

    service.handle(
        new SetSocialLoginPolicyForOrganizationCommand(
            organizationId, true, List.of("GOOGLE"), ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("social_login_policy.set"),
            eq("Organization"),
            eq(organizationId.toString()),
            any());
  }

  @Test
  void rejectsAnUnknownProviderWithoutPersistingAnything() {
    UUID organizationId = UUID.randomUUID();
    SetSocialLoginPolicyForOrganizationCommand command =
        new SetSocialLoginPolicyForOrganizationCommand(
            organizationId, true, List.of("MICROSOFT"), ACTOR);

    assertThatExceptionOfType(UnknownSocialProviderException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizations, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsANonExistentOrganizationWithoutPersistingAnything() {
    UUID nonExistentOrganizationId = UUID.randomUUID();
    when(organizations.findById(nonExistentOrganizationId)).thenReturn(Optional.empty());
    SetSocialLoginPolicyForOrganizationCommand command =
        new SetSocialLoginPolicyForOrganizationCommand(
            nonExistentOrganizationId, true, List.of("GOOGLE"), ACTOR);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizations, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}

package com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization;

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
import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import com.clavaris.organization.domain.model.EmailVerificationMethod;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetAccountAuthenticationPolicyForOrganizationServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private OrganizationRepository organizations;
  private AccountAuthenticationPolicyRepository policies;
  private AuditEventRecorder auditEvents;
  private SetAccountAuthenticationPolicyForOrganizationService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    policies = mock(AccountAuthenticationPolicyRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new SetAccountAuthenticationPolicyForOrganizationService(
            organizations, policies, auditEvents);
  }

  private SetAccountAuthenticationPolicyForOrganizationCommand validCommand(
      final UUID organizationId) {
    return new SetAccountAuthenticationPolicyForOrganizationCommand(
        organizationId,
        false,
        EmailVerificationMethod.LINK,
        false,
        false,
        false,
        false,
        false,
        true,
        false,
        ACTOR);
  }

  @Test
  void definesAFreshPolicyWhenNoneExistsYet() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());

    SetAccountAuthenticationPolicyForOrganizationResult result =
        service.handle(validCommand(organizationId));

    assertThat(result.policy().organizationId()).isEqualTo(organizationId);
    verify(policies).save(result.policy());
  }

  @Test
  void updatesAnExistingPolicyInPlaceRatherThanCreatingASecondOne() {
    UUID organizationId = UUID.randomUUID();
    AccountAuthenticationPolicy existing = AccountAuthenticationPolicy.defaults(organizationId);
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.of(existing));

    SetAccountAuthenticationPolicyForOrganizationResult result =
        service.handle(
            new SetAccountAuthenticationPolicyForOrganizationCommand(
                organizationId,
                true,
                EmailVerificationMethod.CODE,
                true,
                false,
                false,
                false,
                false,
                true,
                false,
                ACTOR));

    assertThat(result.policy().id())
        .as("re-tuning must update the same row, never mint a second one for the same Organization")
        .isEqualTo(existing.id());
    assertThat(result.policy().emailVerificationMethod()).isEqualTo(EmailVerificationMethod.CODE);
  }

  @Test
  void recordsAnAuditEventForTheChange() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());

    service.handle(validCommand(organizationId));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("account_authentication_policy.set"),
            eq("Organization"),
            eq(organizationId.toString()),
            any());
  }

  @Test
  void rejectsANonExistentOrganizationWithoutPersistingAnything() {
    UUID nonExistentOrganizationId = UUID.randomUUID();
    when(organizations.existsById(nonExistentOrganizationId)).thenReturn(false);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(validCommand(nonExistentOrganizationId)));

    verify(policies, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsUsernameRequiredWithoutUsernameSignUpEnabled() {
    UUID organizationId = UUID.randomUUID();
    SetAccountAuthenticationPolicyForOrganizationCommand command =
        new SetAccountAuthenticationPolicyForOrganizationCommand(
            organizationId,
            false,
            EmailVerificationMethod.LINK,
            false,
            false,
            false,
            true,
            false,
            true,
            false,
            ACTOR);

    assertThatExceptionOfType(UsernameRequiredWithoutSignUpException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(organizations, policies, auditEvents);
  }

  @Test
  void rejectsUsernameSignInEnabledWithoutUsernameSignUpEnabled() {
    UUID organizationId = UUID.randomUUID();
    SetAccountAuthenticationPolicyForOrganizationCommand command =
        new SetAccountAuthenticationPolicyForOrganizationCommand(
            organizationId,
            false,
            EmailVerificationMethod.LINK,
            false,
            false,
            false,
            false,
            true,
            true,
            false,
            ACTOR);

    assertThatExceptionOfType(UsernameRequiredWithoutSignUpException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsPasswordOptionalWithNoPasswordlessSignInMethodEnabled() {
    UUID organizationId = UUID.randomUUID();
    SetAccountAuthenticationPolicyForOrganizationCommand command =
        new SetAccountAuthenticationPolicyForOrganizationCommand(
            organizationId,
            false,
            EmailVerificationMethod.LINK,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            ACTOR);

    assertThatExceptionOfType(PasswordOptionalRequiresPasswordlessSignInException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(organizations, policies, auditEvents);
  }

  @Test
  void acceptsPasswordOptionalWhenEmailCodeSignInIsEnabled() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
    SetAccountAuthenticationPolicyForOrganizationCommand command =
        new SetAccountAuthenticationPolicyForOrganizationCommand(
            organizationId,
            false,
            EmailVerificationMethod.LINK,
            true,
            false,
            false,
            false,
            false,
            false,
            false,
            ACTOR);

    SetAccountAuthenticationPolicyForOrganizationResult result = service.handle(command);

    assertThat(result.policy().passwordAtSignUpEnabled()).isFalse();
  }
}

package com.clavaris.organization.application.usecase.createorganization;

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
import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner.ProvisionedSigningKey;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CreateOrganizationServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");
  private static final int DEVELOPMENT_DEFAULT_REQUESTS_PER_MINUTE = 300;
  private static final int HARD_SYSTEM_WIDE_CAP = 6000;

  private OrganizationRepository organizations;
  private SigningKeyProvisioner keyProvisioner;
  private PlatformAccountExistsChecker platformAccountExistsChecker;
  private AuditEventRecorder auditEvents;
  private RateLimitPolicyRepository policies;
  private CreateOrganizationService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    keyProvisioner = mock(SigningKeyProvisioner.class);
    platformAccountExistsChecker = mock(PlatformAccountExistsChecker.class);
    auditEvents = mock(AuditEventRecorder.class);
    policies = mock(RateLimitPolicyRepository.class);
    when(platformAccountExistsChecker.exists(any())).thenReturn(true);
    service =
        new CreateOrganizationService(
            organizations,
            keyProvisioner,
            platformAccountExistsChecker,
            auditEvents,
            policies,
            DEVELOPMENT_DEFAULT_REQUESTS_PER_MINUTE,
            HARD_SYSTEM_WIDE_CAP);
  }

  @Test
  void createsAndPersistsTheOrganization() {
    when(keyProvisioner.provisionFor(any()))
        .thenReturn(new ProvisionedSigningKey(UUID.randomUUID(), "a-kid", "RS256"));

    final CreateOrganizationResult result =
        service.handle(new CreateOrganizationCommand("JobSeeker", UUID.randomUUID(), ACTOR));

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
        service.handle(new CreateOrganizationCommand("JobSeeker", UUID.randomUUID(), ACTOR));

    verify(keyProvisioner).provisionFor(result.organization().id());
    assertThat(result.signingKey().kid()).isEqualTo("a-kid");
    assertThat(result.signingKey().algorithm()).isEqualTo("RS256");
  }

  // TD-SEC-007: this row is exactly what the technical-debt register named as unaudited before
  // this class recorded it — a real regression test, not just "the code compiles".
  @Test
  void recordsAnAuditEventForTheNewlyCreatedOrganization() {
    when(keyProvisioner.provisionFor(any()))
        .thenReturn(new ProvisionedSigningKey(UUID.randomUUID(), "a-kid", "RS256"));

    final CreateOrganizationResult result =
        service.handle(new CreateOrganizationCommand("JobSeeker", UUID.randomUUID(), ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("organization.created"),
            eq("Organization"),
            eq(result.organization().id().toString()),
            any());
  }

  // Security finding (SDE-III review, 2026-08-22), regression test for its fix: before this fix,
  // ownerPlatformAccountId was never checked against a real PlatformAccount anywhere in this path.
  @Test
  void rejectsAnOwnerPlatformAccountIdThatDoesNotExistWithoutPersistingAnything() {
    UUID nonExistentPlatformAccountId = UUID.randomUUID();
    when(platformAccountExistsChecker.exists(nonExistentPlatformAccountId)).thenReturn(false);
    CreateOrganizationCommand command =
        new CreateOrganizationCommand("Ghost Owner Co", nonExistentPlatformAccountId, ACTOR);

    assertThatExceptionOfType(PlatformAccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizations, never()).save(any());
    verifyNoInteractions(keyProvisioner);
    verifyNoInteractions(auditEvents);
  }

  // SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis):
  // Organization.register() defaults every new Organization to DEVELOPMENT (Organization's own
  // Javadoc) — this service must provision a real, explicit, low-default RateLimitPolicy for it.
  @Test
  void provisionsADevelopmentDefaultRateLimitPolicyForTheNewlyCreatedOrganization() {
    when(keyProvisioner.provisionFor(any()))
        .thenReturn(new ProvisionedSigningKey(UUID.randomUUID(), "a-kid", "RS256"));

    final CreateOrganizationResult result =
        service.handle(new CreateOrganizationCommand("JobSeeker", UUID.randomUUID(), ACTOR));

    ArgumentCaptor<RateLimitPolicy> captor = ArgumentCaptor.forClass(RateLimitPolicy.class);
    verify(policies).save(captor.capture());
    assertThat(captor.getValue().organizationId()).isEqualTo(result.organization().id());
    assertThat(captor.getValue().requestsPerMinute())
        .isEqualTo(DEVELOPMENT_DEFAULT_REQUESTS_PER_MINUTE);
  }
}

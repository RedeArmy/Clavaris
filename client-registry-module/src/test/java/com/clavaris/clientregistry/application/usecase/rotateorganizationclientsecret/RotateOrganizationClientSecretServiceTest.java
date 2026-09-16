package com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientSecretGenerator;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SDE-III review, 2026-09-15: this class had no test coverage of its own before the organizationId
 * ownership check {@link RotateOrganizationClientSecretCommand}'s own Javadoc documents — first
 * real coverage, not just a signature-compile fixup.
 */
class RotateOrganizationClientSecretServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final UUID organizationId = UUID.randomUUID();
  private OrganizationClientRepository organizationClients;
  private ClientSecretHasher hasher;
  private OrganizationClientSecretGenerator secretGenerator;
  private AuditEventRecorder auditEvents;
  private RotateOrganizationClientSecretService service;

  @BeforeEach
  void setUp() {
    organizationClients = mock(OrganizationClientRepository.class);
    hasher = mock(ClientSecretHasher.class);
    secretGenerator = mock(OrganizationClientSecretGenerator.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new RotateOrganizationClientSecretService(
            organizationClients, hasher, secretGenerator, auditEvents);
  }

  private OrganizationClient sampleClient() {
    return OrganizationClient.register(
        organizationId,
        "sk_test_target",
        "argon2id$old-hash",
        List.of(PlatformScopes.WORKSPACES_WRITE));
  }

  @Test
  void generatesAFreshSecretHashesItAndPersistsTheRotatedClient() {
    when(organizationClients.findByClientId("sk_test_target"))
        .thenReturn(Optional.of(sampleClient()));
    when(secretGenerator.generate()).thenReturn("a-fresh-raw-secret");
    when(hasher.hash("a-fresh-raw-secret")).thenReturn("argon2id$new-hash");

    RotateOrganizationClientSecretResult result =
        service.handle(
            new RotateOrganizationClientSecretCommand("sk_test_target", organizationId, ACTOR));

    assertThat(result.rawSecret())
        .as("the caller must get back the raw secret exactly once")
        .isEqualTo("a-fresh-raw-secret");
    assertThat(result.clientId()).isEqualTo("sk_test_target");
    verify(organizationClients)
        .save(argThat(saved -> saved.clientSecretHash().equals("argon2id$new-hash")));
  }

  // SDE-III review, 2026-09-15: the platform-tier REST endpoint's own deliberate, unscoped reach —
  // see RotateOrganizationClientSecretCommand's own Javadoc for why null is a legitimate value
  // here, not a bug.
  @Test
  void rotatesSuccessfullyWhenOrganizationIdIsNull() {
    when(organizationClients.findByClientId("sk_test_target"))
        .thenReturn(Optional.of(sampleClient()));
    when(secretGenerator.generate()).thenReturn("a-fresh-raw-secret");
    when(hasher.hash("a-fresh-raw-secret")).thenReturn("argon2id$new-hash");

    RotateOrganizationClientSecretResult result =
        service.handle(new RotateOrganizationClientSecretCommand("sk_test_target", null, ACTOR));

    assertThat(result.rawSecret()).isEqualTo("a-fresh-raw-secret");
  }

  @Test
  void recordsAnAuditEventNamingNeitherTheRawSecretNorItsHash() {
    when(organizationClients.findByClientId("sk_test_target"))
        .thenReturn(Optional.of(sampleClient()));
    when(secretGenerator.generate()).thenReturn("a-fresh-raw-secret");
    when(hasher.hash("a-fresh-raw-secret")).thenReturn("argon2id$new-hash");

    service.handle(
        new RotateOrganizationClientSecretCommand("sk_test_target", organizationId, ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "organization_client.secret_rotated",
            "Organization",
            organizationId.toString(),
            "clientId=sk_test_target");
  }

  @Test
  void rejectsAnUnknownClientIdWithoutGeneratingOrPersistingAnything() {
    when(organizationClients.findByClientId("sk_test_ghost")).thenReturn(Optional.empty());
    RotateOrganizationClientSecretCommand command =
        new RotateOrganizationClientSecretCommand("sk_test_ghost", organizationId, ACTOR);

    assertThatExceptionOfType(OrganizationClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(secretGenerator);
    verifyNoInteractions(hasher);
    verify(organizationClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  // SDE-III review, 2026-09-15 — real regression this guards: before this fix, this method never
  // even accepted an organizationId, so a caller pairing a valid organizationId with a different
  // Organization's own clientId would rotate that Organization's real secret.
  @Test
  void rejectsAClientThatBelongsToADifferentOrganizationWithoutGeneratingOrPersistingAnything() {
    when(organizationClients.findByClientId("sk_test_target"))
        .thenReturn(Optional.of(sampleClient()));
    UUID unrelatedOrganizationId = UUID.randomUUID();
    RotateOrganizationClientSecretCommand command =
        new RotateOrganizationClientSecretCommand("sk_test_target", unrelatedOrganizationId, ACTOR);

    assertThatExceptionOfType(OrganizationClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(secretGenerator);
    verifyNoInteractions(hasher);
    verify(organizationClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}

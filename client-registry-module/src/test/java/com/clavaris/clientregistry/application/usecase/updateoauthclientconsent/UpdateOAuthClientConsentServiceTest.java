package com.clavaris.clientregistry.application.usecase.updateoauthclientconsent;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Same rationale/shape as {@code
 * updateoauthclientgranttypes.UpdateOAuthClientGrantTypesServiceTest}.
 */
class UpdateOAuthClientConsentServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final UUID organizationId = UUID.randomUUID();
  private OAuthClientRepository oauthClients;
  private AuditEventRecorder auditEvents;
  private UpdateOAuthClientConsentService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new UpdateOAuthClientConsentService(oauthClients, auditEvents);
  }

  private OAuthClient sampleClient() {
    return OAuthClient.register(
        organizationId,
        "target-client",
        "argon2id$hashed",
        List.of(),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of());
  }

  @Test
  void savesTheClientWithTheNewConsentSetting() {
    OAuthClient existing = sampleClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    service.handle(
        new UpdateOAuthClientConsentCommand("target-client", organizationId, false, ACTOR));

    verify(oauthClients).save(argThat(saved -> !saved.requireConsent()));
  }

  @Test
  void recordsAnAuditEvent() {
    OAuthClient existing = sampleClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    service.handle(
        new UpdateOAuthClientConsentCommand("target-client", organizationId, false, ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "oauth_client.consent_updated",
            "Organization",
            organizationId.toString(),
            "clientId=target-client");
  }

  @Test
  void rejectsAnUnknownClientIdWithoutPersistingOrRecordingAnything() {
    when(oauthClients.findByClientId("ghost-client")).thenReturn(Optional.empty());
    UpdateOAuthClientConsentCommand command =
        new UpdateOAuthClientConsentCommand("ghost-client", organizationId, false, ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAClientThatBelongsToADifferentOrganizationWithoutPersistingOrRecordingAnything() {
    OAuthClient existing = sampleClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));
    UUID unrelatedOrganizationId = UUID.randomUUID();
    UpdateOAuthClientConsentCommand command =
        new UpdateOAuthClientConsentCommand("target-client", unrelatedOrganizationId, false, ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAnInactiveClientWithoutPersistingOrRecordingAnything() {
    OAuthClient existing = sampleClient().deactivate();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));
    UpdateOAuthClientConsentCommand command =
        new UpdateOAuthClientConsentCommand("target-client", organizationId, false, ACTOR);

    assertThatExceptionOfType(OAuthClientInactiveException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}

package com.clavaris.clientregistry.application.usecase.activateoauthclient;

import static org.assertj.core.api.Assertions.assertThat;
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

/** Same rationale/shape as {@code deactivateoauthclient.DeactivateOAuthClientServiceTest}. */
class ActivateOAuthClientServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final UUID organizationId = UUID.randomUUID();
  private OAuthClientRepository oauthClients;
  private AuditEventRecorder auditEvents;
  private ActivateOAuthClientService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new ActivateOAuthClientService(oauthClients, auditEvents);
  }

  private OAuthClient sampleInactiveClient() {
    OAuthClient active =
        OAuthClient.register(
            organizationId,
            "target-client",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    return active.deactivate();
  }

  @Test
  void savesTheClientWithActiveTrue() {
    OAuthClient existing = sampleInactiveClient();
    assertThat(existing.active()).isFalse();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    service.handle(new ActivateOAuthClientCommand("target-client", organizationId, ACTOR));

    verify(oauthClients).save(argThat(OAuthClient::active));
  }

  @Test
  void recordsAnAuditEvent() {
    OAuthClient existing = sampleInactiveClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    service.handle(new ActivateOAuthClientCommand("target-client", organizationId, ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "oauth_client.activated",
            "Organization",
            organizationId.toString(),
            "clientId=target-client");
  }

  @Test
  void rejectsAnUnknownClientIdWithoutPersistingOrRecordingAnything() {
    when(oauthClients.findByClientId("ghost-client")).thenReturn(Optional.empty());
    ActivateOAuthClientCommand command =
        new ActivateOAuthClientCommand("ghost-client", organizationId, ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAClientThatBelongsToADifferentOrganizationWithoutPersistingOrRecordingAnything() {
    OAuthClient existing = sampleInactiveClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));
    UUID unrelatedOrganizationId = UUID.randomUUID();
    ActivateOAuthClientCommand command =
        new ActivateOAuthClientCommand("target-client", unrelatedOrganizationId, ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}

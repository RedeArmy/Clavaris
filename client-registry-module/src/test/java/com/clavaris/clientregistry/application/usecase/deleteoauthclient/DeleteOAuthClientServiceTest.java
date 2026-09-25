package com.clavaris.clientregistry.application.usecase.deleteoauthclient;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
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

class DeleteOAuthClientServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final UUID organizationId = UUID.randomUUID();
  private OAuthClientRepository oauthClients;
  private OAuthClientTokenRevoker tokenRevoker;
  private AuditEventRecorder auditEvents;
  private DeleteOAuthClientService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    tokenRevoker = mock(OAuthClientTokenRevoker.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new DeleteOAuthClientService(oauthClients, tokenRevoker, auditEvents);
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
  void revokesTokensThenDeletesTheClient() {
    OAuthClient existing = sampleInactiveClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    service.handle(new DeleteOAuthClientCommand("target-client", organizationId, ACTOR));

    verify(tokenRevoker).revokeAllTokensFor(existing.id());
    verify(oauthClients).delete(existing);
  }

  @Test
  void recordsAnAuditEvent() {
    OAuthClient existing = sampleInactiveClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    service.handle(new DeleteOAuthClientCommand("target-client", organizationId, ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "oauth_client.deleted",
            "Organization",
            organizationId.toString(),
            "deletedClientId=target-client");
  }

  @Test
  void rejectsAnUnknownClientIdWithoutDeletingOrRecordingAnything() {
    when(oauthClients.findByClientId("ghost-client")).thenReturn(Optional.empty());
    DeleteOAuthClientCommand command =
        new DeleteOAuthClientCommand("ghost-client", organizationId, ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).delete(any());
    verifyNoInteractions(tokenRevoker);
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAClientThatBelongsToADifferentOrganizationWithoutDeletingOrRecordingAnything() {
    OAuthClient existing = sampleInactiveClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));
    UUID unrelatedOrganizationId = UUID.randomUUID();
    DeleteOAuthClientCommand command =
        new DeleteOAuthClientCommand("target-client", unrelatedOrganizationId, ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).delete(any());
    verifyNoInteractions(tokenRevoker);
    verifyNoInteractions(auditEvents);
  }

  // The one guard deactivation never needed — see OAuthClientActiveException's own Javadoc.
  @Test
  void rejectsAnActiveClientWithoutDeletingOrRecordingAnything() {
    OAuthClient existing =
        OAuthClient.register(
            organizationId,
            "target-client",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));
    DeleteOAuthClientCommand command =
        new DeleteOAuthClientCommand("target-client", organizationId, ACTOR);

    assertThatExceptionOfType(OAuthClientActiveException.class)
        .isThrownBy(() -> service.handle(command));

    verify(oauthClients, never()).delete(any());
    verifyNoInteractions(tokenRevoker);
    verifyNoInteractions(auditEvents);
  }
}

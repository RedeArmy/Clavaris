package com.clavaris.clientregistry.application.usecase.updateoauthclientpkcepolicy;

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
import com.clavaris.clientregistry.domain.model.PkcePolicy;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ADR-0043. Same shape as {@code UpdateOAuthClientConsentServiceTest}, plus what is new here. */
class UpdateOAuthClientPkcePolicyServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final UUID organizationId = UUID.randomUUID();
  private OAuthClientRepository oauthClients;
  private AuditEventRecorder auditEvents;
  private UpdateOAuthClientPkcePolicyService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new UpdateOAuthClientPkcePolicyService(oauthClients, auditEvents);
  }

  private OAuthClient sampleClient(final PkcePolicy policy) {
    return OAuthClient.register(
        organizationId,
        "target-client",
        "argon2id$hashed",
        List.of(),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        policy,
        List.of());
  }

  private UpdateOAuthClientPkcePolicyCommand command(final PkcePolicy policy) {
    return new UpdateOAuthClientPkcePolicyCommand("target-client", organizationId, policy, ACTOR);
  }

  @Test
  void savesTheClientWithTheNewPolicy() {
    when(oauthClients.findByClientId("target-client"))
        .thenReturn(Optional.of(sampleClient(PkcePolicy.REQUIRED)));

    service.handle(command(PkcePolicy.OPTIONAL));

    verify(oauthClients).save(argThat(saved -> saved.pkcePolicy() == PkcePolicy.OPTIONAL));
  }

  @Test
  void auditsARelaxingChangeWithWhatItWasAndWhatItBecame() {
    when(oauthClients.findByClientId("target-client"))
        .thenReturn(Optional.of(sampleClient(PkcePolicy.REQUIRED)));

    service.handle(command(PkcePolicy.OPTIONAL));

    verify(auditEvents)
        .write(
            ACTOR,
            "oauth_client.pkce_policy_updated",
            "Organization",
            organizationId.toString(),
            "clientId=target-client from=REQUIRED to=OPTIONAL");
  }

  @Test
  void auditsATighteningChangeTheSameWay() {
    when(oauthClients.findByClientId("target-client"))
        .thenReturn(Optional.of(sampleClient(PkcePolicy.OPTIONAL)));

    service.handle(command(PkcePolicy.REQUIRED));

    verify(auditEvents)
        .write(
            ACTOR,
            "oauth_client.pkce_policy_updated",
            "Organization",
            organizationId.toString(),
            "clientId=target-client from=OPTIONAL to=REQUIRED");
  }

  @Test
  void askingForThePolicyTheClientAlreadyHasSavesAndRecordsNothing() {
    when(oauthClients.findByClientId("target-client"))
        .thenReturn(Optional.of(sampleClient(PkcePolicy.REQUIRED)));

    service.handle(command(PkcePolicy.REQUIRED));

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAnUnknownClientIdWithoutPersistingOrRecordingAnything() {
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.empty());

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command(PkcePolicy.OPTIONAL)))
        .withMessageContaining("target-client");

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAClientThatBelongsToADifferentOrganizationWithoutPersistingOrRecordingAnything() {
    when(oauthClients.findByClientId("target-client"))
        .thenReturn(Optional.of(sampleClient(PkcePolicy.REQUIRED)));
    UpdateOAuthClientPkcePolicyCommand foreign =
        new UpdateOAuthClientPkcePolicyCommand(
            "target-client", UUID.randomUUID(), PkcePolicy.OPTIONAL, ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(foreign));

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAnInactiveClientWithoutPersistingOrRecordingAnything() {
    when(oauthClients.findByClientId("target-client"))
        .thenReturn(Optional.of(sampleClient(PkcePolicy.REQUIRED).deactivate()));

    assertThatExceptionOfType(OAuthClientInactiveException.class)
        .isThrownBy(() -> service.handle(command(PkcePolicy.OPTIONAL)))
        .withMessageContaining("inactive");

    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void theSavedClientKeepsEverythingElseAboutIt() {
    OAuthClient existing = sampleClient(PkcePolicy.REQUIRED);
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    service.handle(command(PkcePolicy.OPTIONAL));

    verify(oauthClients)
        .save(
            argThat(
                saved ->
                    saved.id().equals(existing.id())
                        && saved.clientSecretHash().equals(existing.clientSecretHash())
                        && saved.requireConsent() == existing.requireConsent()
                        && saved.version() == existing.version()));
    assertThat(existing.pkcePolicy()).isEqualTo(PkcePolicy.REQUIRED);
  }
}

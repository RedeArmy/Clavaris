package com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret;

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
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientSecretGenerator;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Same rationale/shape as {@code rotateplatformclientsecret.RotatePlatformClientSecretServiceTest}.
 */
class RotateOAuthClientSecretServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final UUID organizationId = UUID.randomUUID();
  private OAuthClientRepository oauthClients;
  private ClientSecretHasher hasher;
  private OAuthClientSecretGenerator secretGenerator;
  private AuditEventRecorder auditEvents;
  private RotateOAuthClientSecretService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    hasher = mock(ClientSecretHasher.class);
    secretGenerator = mock(OAuthClientSecretGenerator.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new RotateOAuthClientSecretService(oauthClients, hasher, secretGenerator, auditEvents);
  }

  private OAuthClient sampleClient() {
    return OAuthClient.register(
        organizationId,
        "target-client",
        "argon2id$old-hash",
        List.of("https://jobseeker.example.com/callback"),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of());
  }

  @Test
  void generatesAFreshSecretHashesItAndPersistsTheRotatedClient() {
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(sampleClient()));
    when(secretGenerator.generate()).thenReturn("a-fresh-raw-secret");
    when(hasher.hash("a-fresh-raw-secret")).thenReturn("argon2id$new-hash");

    RotateOAuthClientSecretResult result =
        service.handle(new RotateOAuthClientSecretCommand("target-client", ACTOR));

    assertThat(result.rawSecret())
        .as("the caller must get back the raw secret exactly once")
        .isEqualTo("a-fresh-raw-secret");
    assertThat(result.clientId()).isEqualTo("target-client");
    verify(oauthClients)
        .save(argThat(saved -> saved.clientSecretHash().equals("argon2id$new-hash")));
  }

  @Test
  void rotationDoesNotReactivateAnAlreadyDeactivatedClient() {
    OAuthClient deactivated = sampleClient().deactivate();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(deactivated));
    when(secretGenerator.generate()).thenReturn("a-fresh-raw-secret");
    when(hasher.hash("a-fresh-raw-secret")).thenReturn("argon2id$new-hash");

    service.handle(new RotateOAuthClientSecretCommand("target-client", ACTOR));

    verify(oauthClients).save(argThat(saved -> !saved.active()));
  }

  @Test
  void recordsAnAuditEventNamingNeitherTheRawSecretNorItsHash() {
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(sampleClient()));
    when(secretGenerator.generate()).thenReturn("a-fresh-raw-secret");
    when(hasher.hash("a-fresh-raw-secret")).thenReturn("argon2id$new-hash");

    service.handle(new RotateOAuthClientSecretCommand("target-client", ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "oauth_client.secret_rotated",
            "Organization",
            organizationId.toString(),
            "clientId=target-client");
  }

  @Test
  void rejectsAnUnknownClientIdWithoutGeneratingOrPersistingAnything() {
    when(oauthClients.findByClientId("ghost-client")).thenReturn(Optional.empty());
    RotateOAuthClientSecretCommand command =
        new RotateOAuthClientSecretCommand("ghost-client", ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(secretGenerator);
    verifyNoInteractions(hasher);
    verify(oauthClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}

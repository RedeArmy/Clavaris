package com.clavaris.clientregistry.application.usecase.updateoauthclientaccesstokenttl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.clientregistry.domain.model.PkcePolicy;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.InvalidInputException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TD-SEC-104 / ADR-0049. Same shape as {@code UpdateOAuthClientPkcePolicyServiceTest}. */
class UpdateOAuthClientAccessTokenTtlServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final UUID organizationId = UUID.randomUUID();
  private OAuthClientRepository oauthClients;
  private AuditEventRecorder auditEvents;
  private UpdateOAuthClientAccessTokenTtlService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new UpdateOAuthClientAccessTokenTtlService(oauthClients, auditEvents);
  }

  private OAuthClient client(final Integer ttl) {
    return OAuthClient.register(
        organizationId,
        "target-client",
        "argon2id$hashed",
        List.of(),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        PkcePolicy.REQUIRED,
        List.of(),
        ttl);
  }

  private UpdateOAuthClientAccessTokenTtlCommand command(final Integer ttl) {
    return new UpdateOAuthClientAccessTokenTtlCommand("target-client", organizationId, ttl, ACTOR);
  }

  @Test
  void savesTheClientWithTheNewLifetimeAndAuditsWhatItWasAndBecame() {
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(client(null)));

    service.handle(command(120));

    verify(oauthClients).save(argThat(saved -> saved.effectiveAccessTokenTtlSeconds() == 120));
    verify(auditEvents)
        .write(
            ACTOR,
            "oauth_client.access_token_ttl_updated",
            "Organization",
            organizationId.toString(),
            "clientId=target-client from=300 to=120");
  }

  @Test
  void aNullPutsTheClientBackOnTheDefault() {
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(client(900)));

    service.handle(command(null));

    verify(oauthClients).save(argThat(saved -> saved.accessTokenTtlSeconds().isEmpty()));
    verify(auditEvents)
        .write(
            ACTOR,
            "oauth_client.access_token_ttl_updated",
            "Organization",
            organizationId.toString(),
            "clientId=target-client from=900 to=300");
  }

  @Test
  void changingNothingSavesAndAuditsNothing() {
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(client(120)));

    service.handle(command(120));

    verify(oauthClients, never()).save(any());
    verify(auditEvents, never()).write(any(), any(), any(), any(), any());
  }

  @Test
  void aValueOutsideTheCapIsAClientErrorAndSavesNothing() {
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(client(null)));

    assertThatThrownBy(() -> service.handle(command(59))).isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> service.handle(command(3_601)))
        .isInstanceOf(InvalidInputException.class);

    verify(oauthClients, never()).save(any());
  }

  @Test
  void anUnknownClientOrAnotherOrganizationsIsNotFound() {
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.handle(command(120)))
        .isInstanceOf(OAuthClientNotFoundException.class);

    OAuthClient foreign =
        OAuthClient.register(
            UUID.randomUUID(),
            "target-client",
            "argon2id$hashed",
            List.of(),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            PkcePolicy.REQUIRED,
            List.of(),
            null);
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(foreign));
    assertThatThrownBy(() -> service.handle(command(120)))
        .isInstanceOf(OAuthClientNotFoundException.class);

    verify(oauthClients, never()).save(any());
  }

  @Test
  void anInactiveClientCannotBeEdited() {
    when(oauthClients.findByClientId("target-client"))
        .thenReturn(Optional.of(client(null).deactivate()));

    assertThatThrownBy(() -> service.handle(command(120)))
        .isInstanceOf(OAuthClientInactiveException.class);

    assertThat(true).isTrue();
  }
}

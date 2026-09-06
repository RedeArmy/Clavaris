package com.clavaris.clientregistry.application.usecase.requestclientdomainconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestClientDomainConfigServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private OAuthClientRepository oauthClients;
  private ClientDomainConfigRepository domainConfigs;
  private AuditEventRecorder auditEvents;
  private RequestClientDomainConfigService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    domainConfigs = mock(ClientDomainConfigRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new RequestClientDomainConfigService(oauthClients, domainConfigs, auditEvents);
  }

  private OAuthClient registeredClient(final UUID organizationId) {
    return OAuthClient.register(
        organizationId,
        "test_client",
        "hashed-secret",
        List.of("https://app.example.com/callback"),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of());
  }

  @Test
  void createsAFreshPendingRequestWhenNoneExistsYet() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(domainConfigs.findByHostname("login.example.com")).thenReturn(Optional.empty());
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.empty());

    RequestClientDomainConfigResult result =
        service.handle(
            new RequestClientDomainConfigCommand(
                organizationId,
                client.id(),
                ClientDomainMode.CNAME,
                "login.example.com",
                null,
                ACTOR));

    assertThat(result.config().oauthClientId()).isEqualTo(client.id());
    assertThat(result.config().hostname()).contains("login.example.com");
    verify(domainConfigs).save(result.config());
  }

  @Test
  void reRequestsInPlaceWhenThisClientAlreadyHasADomain() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    ClientDomainConfig existing =
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, "old.example.com", null);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(domainConfigs.findByHostname("new.example.com")).thenReturn(Optional.empty());
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.of(existing));

    RequestClientDomainConfigResult result =
        service.handle(
            new RequestClientDomainConfigCommand(
                organizationId,
                client.id(),
                ClientDomainMode.PROXY,
                "new.example.com",
                null,
                ACTOR));

    assertThat(result.config().id())
        .as("re-requesting must update the same row, never mint a second one for the same client")
        .isEqualTo(existing.id());
    assertThat(result.config().hostname()).contains("new.example.com");
  }

  @Test
  void recordsAnAuditEventForTheRequest() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(domainConfigs.findByHostname(any())).thenReturn(Optional.empty());
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.empty());

    service.handle(
        new RequestClientDomainConfigCommand(
            organizationId, client.id(), ClientDomainMode.CNAME, "login.example.com", null, ACTOR));

    verify(auditEvents)
        .write(
            org.mockito.ArgumentMatchers.eq(ACTOR),
            org.mockito.ArgumentMatchers.eq("client_domain_config.requested"),
            org.mockito.ArgumentMatchers.eq("OAuthClient"),
            org.mockito.ArgumentMatchers.eq(client.id().toString()),
            any());
  }

  @Test
  void rejectsANonExistentOAuthClientWithoutPersistingAnything() {
    UUID organizationId = UUID.randomUUID();
    UUID nonExistentClientId = UUID.randomUUID();
    when(oauthClients.findById(nonExistentClientId)).thenReturn(Optional.empty());
    RequestClientDomainConfigCommand command =
        new RequestClientDomainConfigCommand(
            organizationId,
            nonExistentClientId,
            ClientDomainMode.CNAME,
            "login.example.com",
            null,
            ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(domainConfigs, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAClientThatBelongsToADifferentOrganizationWithoutPersistingAnything() {
    OAuthClient client = registeredClient(UUID.randomUUID());
    UUID unrelatedOrganizationId = UUID.randomUUID();
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    RequestClientDomainConfigCommand command =
        new RequestClientDomainConfigCommand(
            unrelatedOrganizationId,
            client.id(),
            ClientDomainMode.CNAME,
            "login.example.com",
            null,
            ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(domainConfigs, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAHostnameAlreadyClaimedByADifferentClientWithoutPersistingAnything() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    ClientDomainConfig claimedByAnotherClient =
        ClientDomainConfig.request(
            UUID.randomUUID(), ClientDomainMode.CNAME, "taken.example.com", null);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(domainConfigs.findByHostname("taken.example.com"))
        .thenReturn(Optional.of(claimedByAnotherClient));
    RequestClientDomainConfigCommand command =
        new RequestClientDomainConfigCommand(
            organizationId, client.id(), ClientDomainMode.CNAME, "taken.example.com", null, ACTOR);

    assertThatExceptionOfType(HostnameAlreadyClaimedException.class)
        .isThrownBy(() -> service.handle(command));

    verify(domainConfigs, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void allowsReRequestingTheSameHostnameForTheSameClient() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    ClientDomainConfig existing =
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, "login.example.com", null);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(domainConfigs.findByHostname("login.example.com")).thenReturn(Optional.of(existing));
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.of(existing));

    RequestClientDomainConfigResult result =
        service.handle(
            new RequestClientDomainConfigCommand(
                organizationId,
                client.id(),
                ClientDomainMode.PROXY,
                "login.example.com",
                null,
                ACTOR));

    assertThat(result.config().mode()).contains(ClientDomainMode.PROXY);
  }
}

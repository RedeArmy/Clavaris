package com.clavaris.clientregistry.application.usecase.verifyclientdomainownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import com.clavaris.clientregistry.domain.model.DomainVerificationStatus;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VerifyClientDomainOwnershipServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private OAuthClientRepository oauthClients;
  private ClientDomainConfigRepository domainConfigs;
  private DnsTxtRecordLookup dnsLookup;
  private AuditEventRecorder auditEvents;
  private VerifyClientDomainOwnershipService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    domainConfigs = mock(ClientDomainConfigRepository.class);
    dnsLookup = mock(DnsTxtRecordLookup.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new VerifyClientDomainOwnershipService(oauthClients, domainConfigs, dnsLookup, auditEvents);
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
  void marksVerifiedWhenTheDnsTxtRecordMatchesTheChallengeToken() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    ClientDomainConfig pending =
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, "login.example.com", null);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.of(pending));
    when(dnsLookup.lookupTxtRecords("_clavaris-challenge.login.example.com"))
        .thenReturn(List.of(pending.dnsTxtChallengeToken().orElseThrow()));

    VerifyClientDomainOwnershipResult result =
        service.handle(new VerifyClientDomainOwnershipCommand(organizationId, client.id(), ACTOR));

    assertThat(result.config().verificationStatus()).contains(DomainVerificationStatus.VERIFIED);
    assertThat(result.config().isVerified()).isTrue();
    verify(domainConfigs).save(result.config());
  }

  @Test
  void marksFailedWhenTheDnsTxtRecordDoesNotMatch() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    ClientDomainConfig pending =
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, "login.example.com", null);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.of(pending));
    when(dnsLookup.lookupTxtRecords("_clavaris-challenge.login.example.com"))
        .thenReturn(List.of("some-other-value"));

    VerifyClientDomainOwnershipResult result =
        service.handle(new VerifyClientDomainOwnershipCommand(organizationId, client.id(), ACTOR));

    assertThat(result.config().verificationStatus()).contains(DomainVerificationStatus.FAILED);
    assertThat(result.config().isVerified()).isFalse();
  }

  @Test
  void marksFailedWhenTheDnsLookupReturnsNoRecordsAtAll() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    ClientDomainConfig pending =
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, "login.example.com", null);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.of(pending));
    when(dnsLookup.lookupTxtRecords(any())).thenReturn(List.of());

    VerifyClientDomainOwnershipResult result =
        service.handle(new VerifyClientDomainOwnershipCommand(organizationId, client.id(), ACTOR));

    assertThat(result.config().verificationStatus()).contains(DomainVerificationStatus.FAILED);
  }

  @Test
  void recordsAVerifiedAuditEventOnSuccess() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    ClientDomainConfig pending =
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, "login.example.com", null);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.of(pending));
    when(dnsLookup.lookupTxtRecords(any()))
        .thenReturn(List.of(pending.dnsTxtChallengeToken().orElseThrow()));

    service.handle(new VerifyClientDomainOwnershipCommand(organizationId, client.id(), ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("client_domain_config.verified"),
            eq("OAuthClient"),
            eq(client.id().toString()),
            any());
  }

  @Test
  void rejectsANonExistentOAuthClientWithoutPersistingAnything() {
    UUID organizationId = UUID.randomUUID();
    UUID nonExistentClientId = UUID.randomUUID();
    when(oauthClients.findById(nonExistentClientId)).thenReturn(Optional.empty());
    VerifyClientDomainOwnershipCommand command =
        new VerifyClientDomainOwnershipCommand(organizationId, nonExistentClientId, ACTOR);

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(domainConfigs, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAClientThatHasNeverRequestedADomain() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.empty());
    VerifyClientDomainOwnershipCommand command =
        new VerifyClientDomainOwnershipCommand(organizationId, client.id(), ACTOR);

    assertThatExceptionOfType(ClientDomainConfigNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(domainConfigs, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}

package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationEnvironmentChecker;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-0009 §4: proves the production/development split BR-CLIENT-04 requires. */
class OAuthClientEmbeddingEligibilityCheckerTest {

  private final OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
  private final ClientDomainConfigRepository domainConfigs =
      mock(ClientDomainConfigRepository.class);
  private final OrganizationEnvironmentChecker environmentChecker =
      mock(OrganizationEnvironmentChecker.class);
  private final OAuthClientEmbeddingEligibilityChecker checker =
      new OAuthClientEmbeddingEligibilityChecker(oauthClients, domainConfigs, environmentChecker);

  @Test
  void resolvesEmptyForANullClientId() {
    assertThat(checker.resolveAllowedFrameAncestor(null)).isEmpty();
  }

  @Test
  void resolvesEmptyForAnUnknownClientId() {
    when(oauthClients.findByClientId("unknown-client")).thenReturn(Optional.empty());

    assertThat(checker.resolveAllowedFrameAncestor("unknown-client")).isEmpty();
  }

  @Test
  void aDevelopmentOrganizationsClientIsEmbeddingEligibleWithTheWildcardOrigin() {
    OAuthClient client = anOAuthClient();
    when(oauthClients.findByClientId("dev-client")).thenReturn(Optional.of(client));
    when(environmentChecker.isDevelopment(client.organizationId())).thenReturn(true);

    assertThat(checker.resolveAllowedFrameAncestor("dev-client")).contains("*");
  }

  @Test
  void aProductionClientWithAVerifiedDomainAndEmbeddingOriginIsEligible() {
    OAuthClient client = anOAuthClient();
    when(oauthClients.findByClientId("prod-client")).thenReturn(Optional.of(client));
    when(environmentChecker.isDevelopment(client.organizationId())).thenReturn(false);
    ClientDomainConfig verified =
        ClientDomainConfig.request(
                client.id(), ClientDomainMode.CNAME, "login.example.com", "https://app.example.com")
            .markVerified();
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.of(verified));

    assertThat(checker.resolveAllowedFrameAncestor("prod-client"))
        .contains("https://app.example.com");
  }

  @Test
  void aProductionClientWithoutAVerifiedDomainIsNotEligible() {
    OAuthClient client = anOAuthClient();
    when(oauthClients.findByClientId("prod-client")).thenReturn(Optional.of(client));
    when(environmentChecker.isDevelopment(client.organizationId())).thenReturn(false);
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.empty());

    assertThat(checker.resolveAllowedFrameAncestor("prod-client")).isEmpty();
  }

  @Test
  void aProductionClientWithAVerifiedDomainButNoEmbeddingOriginIsNotEligible() {
    OAuthClient client = anOAuthClient();
    when(oauthClients.findByClientId("prod-client")).thenReturn(Optional.of(client));
    when(environmentChecker.isDevelopment(client.organizationId())).thenReturn(false);
    ClientDomainConfig verifiedNoOrigin =
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, "login.example.com", null)
            .markVerified();
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.of(verifiedNoOrigin));

    assertThat(checker.resolveAllowedFrameAncestor("prod-client")).isEmpty();
  }

  @Test
  void aProductionClientWithAPendingDomainIsNotEligible() {
    OAuthClient client = anOAuthClient();
    when(oauthClients.findByClientId("prod-client")).thenReturn(Optional.of(client));
    when(environmentChecker.isDevelopment(client.organizationId())).thenReturn(false);
    ClientDomainConfig pending =
        ClientDomainConfig.request(
            client.id(), ClientDomainMode.CNAME, "login.example.com", "https://app.example.com");
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.of(pending));

    assertThat(checker.resolveAllowedFrameAncestor("prod-client")).isEmpty();
  }

  private static OAuthClient anOAuthClient() {
    return OAuthClient.register(
        UUID.randomUUID(),
        "a-client",
        "argon2id$hashed",
        List.of("https://app.example.com/callback"),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of());
  }
}

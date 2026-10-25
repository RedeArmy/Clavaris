package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationEnvironmentChecker;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationEmbeddingSessionPolicyTest {

  private final OrganizationEnvironmentChecker environments =
      mock(OrganizationEnvironmentChecker.class);
  private final ClientDomainConfigRepository domainConfigs =
      mock(ClientDomainConfigRepository.class);
  private final OrganizationEmbeddingSessionPolicy policy =
      new OrganizationEmbeddingSessionPolicy(environments, domainConfigs, 60, 100);

  @Test
  void aDevelopmentOrganizationCanBeEmbeddedWithoutAskingAboutItsDomains() {
    UUID organizationId = UUID.randomUUID();
    when(environments.isDevelopment(organizationId)).thenReturn(true);

    assertThat(policy.allowsCrossSiteSession(organizationId)).isTrue();

    verifyNoInteractions(domainConfigs);
  }

  @Test
  void aProductionOrganizationCanBeEmbeddedOnceAClientHasAVerifiedEmbeddingDomain() {
    UUID organizationId = UUID.randomUUID();
    when(environments.isDevelopment(organizationId)).thenReturn(false);
    when(domainConfigs.existsVerifiedEmbeddingByOrganizationId(organizationId)).thenReturn(true);

    assertThat(policy.allowsCrossSiteSession(organizationId)).isTrue();
  }

  @Test
  void aProductionOrganizationNobodyEmbeddedCannot() {
    UUID organizationId = UUID.randomUUID();
    when(environments.isDevelopment(organizationId)).thenReturn(false);
    when(domainConfigs.existsVerifiedEmbeddingByOrganizationId(organizationId)).thenReturn(false);

    assertThat(policy.allowsCrossSiteSession(organizationId)).isFalse();
  }

  // Sessions are written on every sign-in; the answer must not cost a query each time.
  @Test
  void theAnswerIsCachedPerOrganization() {
    UUID organizationId = UUID.randomUUID();
    when(environments.isDevelopment(organizationId)).thenReturn(false);
    when(domainConfigs.existsVerifiedEmbeddingByOrganizationId(organizationId)).thenReturn(false);

    policy.allowsCrossSiteSession(organizationId);
    policy.allowsCrossSiteSession(organizationId);
    policy.allowsCrossSiteSession(organizationId);

    verify(domainConfigs, times(1)).existsVerifiedEmbeddingByOrganizationId(organizationId);
  }

  @Test
  void organizationsAreAnsweredIndependently() {
    UUID embedded = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    when(environments.isDevelopment(embedded)).thenReturn(false);
    when(environments.isDevelopment(other)).thenReturn(false);
    when(domainConfigs.existsVerifiedEmbeddingByOrganizationId(embedded)).thenReturn(true);
    when(domainConfigs.existsVerifiedEmbeddingByOrganizationId(other)).thenReturn(false);

    assertThat(policy.allowsCrossSiteSession(embedded)).isTrue();
    assertThat(policy.allowsCrossSiteSession(other)).isFalse();
  }
}

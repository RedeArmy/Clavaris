package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CustomDomainRequestRewriteFilterTest {

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
  void forwardsToTheResolvedOrganizationWhenTheHostMatchesAVerifiedDomain() throws Exception {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    ClientDomainConfig verified =
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, "login.example.com", null)
            .markVerified();
    ClientDomainConfigRepository domainConfigs = mock(ClientDomainConfigRepository.class);
    OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
    when(domainConfigs.findByHostname("login.example.com")).thenReturn(Optional.of(verified));
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    CustomDomainRequestRewriteFilter filter =
        new CustomDomainRequestRewriteFilter(domainConfigs, oauthClients);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
    request.setServerName("login.example.com");
    request.setQueryString("client_id=" + client.clientId());
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getForwardedUrl()).isEqualTo("/o/" + organizationId + "/oauth2/authorize");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void passesThroughUnchangedWhenTheHostMatchesNoDomainConfig() throws Exception {
    ClientDomainConfigRepository domainConfigs = mock(ClientDomainConfigRepository.class);
    OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
    when(domainConfigs.findByHostname("unrelated.example.com")).thenReturn(Optional.empty());
    CustomDomainRequestRewriteFilter filter =
        new CustomDomainRequestRewriteFilter(domainConfigs, oauthClients);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
    request.setServerName("unrelated.example.com");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getForwardedUrl()).isNull();
    assertThat(chain.getRequest()).isNotNull();
  }

  // BR-CLIENT-04: a PENDING or FAILED domain must never route real traffic — same invariant
  // ClientDomainConfig#isVerified itself exists to make impossible to check incorrectly.
  @Test
  void passesThroughUnchangedWhenTheDomainHasNotBeenVerifiedYet() throws Exception {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    ClientDomainConfig pending =
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, "login.example.com", null);
    ClientDomainConfigRepository domainConfigs = mock(ClientDomainConfigRepository.class);
    OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
    when(domainConfigs.findByHostname("login.example.com")).thenReturn(Optional.of(pending));
    CustomDomainRequestRewriteFilter filter =
        new CustomDomainRequestRewriteFilter(domainConfigs, oauthClients);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
    request.setServerName("login.example.com");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getForwardedUrl()).isNull();
    assertThat(chain.getRequest()).isNotNull();
    verify(oauthClients, never()).findById(client.id());
  }

  @Test
  void neverForwardsAPathThatAlreadyStartsWithTheOrganizationPrefix() throws Exception {
    UUID organizationId = UUID.randomUUID();
    ClientDomainConfigRepository domainConfigs = mock(ClientDomainConfigRepository.class);
    OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
    CustomDomainRequestRewriteFilter filter =
        new CustomDomainRequestRewriteFilter(domainConfigs, oauthClients);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/o/" + organizationId + "/oauth2/authorize");
    request.setServerName("login.example.com");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getForwardedUrl()).isNull();
    assertThat(chain.getRequest()).isNotNull();
    verify(domainConfigs, never()).findByHostname(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void neverRewritesStaticAssetRequestsEvenOnAVerifiedCustomDomain() throws Exception {
    ClientDomainConfigRepository domainConfigs = mock(ClientDomainConfigRepository.class);
    OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
    CustomDomainRequestRewriteFilter filter =
        new CustomDomainRequestRewriteFilter(domainConfigs, oauthClients);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/js/login-submit-guard.js");
    request.setServerName("login.example.com");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getForwardedUrl()).isNull();
    assertThat(chain.getRequest()).isNotNull();
    verify(domainConfigs, never()).findByHostname(org.mockito.ArgumentMatchers.any());
  }
}

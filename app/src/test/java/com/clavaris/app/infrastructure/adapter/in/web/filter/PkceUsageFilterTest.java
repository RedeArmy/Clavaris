package com.clavaris.app.infrastructure.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.clientregistry.domain.model.PkcePolicy;
import com.clavaris.common.application.port.SecurityMetricsRecorder;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * ADR-0043: the counter that says whether anyone uses the relaxed policy. It must count the
 * Organization authorize endpoint and nothing else, tag a request without a challenge with the
 * client's stored policy, and never change what happens to the request.
 */
class PkceUsageFilterTest {

  private static final String AUTHORIZE =
      "/o/6f1c2d4e-8a3b-4c5d-9e7f-0a1b2c3d4e5f/oauth2/authorize";
  private static final String CLIENT = "client_abc";

  private final OAuthClientRepository clients = mock(OAuthClientRepository.class);
  private final SecurityMetricsRecorder metrics = mock(SecurityMetricsRecorder.class);
  private final PkceUsageFilter filter = new PkceUsageFilter(clients, metrics);

  private MockFilterChain run(final MockHttpServletRequest request) throws Exception {
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return chain;
  }

  private static MockHttpServletRequest authorize(final String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    request.setParameter("client_id", CLIENT);
    return request;
  }

  private void clientWith(final PkcePolicy policy, final boolean active) {
    OAuthClient client = mock(OAuthClient.class);
    when(client.active()).thenReturn(active);
    when(client.pkcePolicy()).thenReturn(policy);
    when(clients.findByClientId(CLIENT)).thenReturn(Optional.of(client));
  }

  @Test
  void aRequestWithAChallengeIsCountedAsPresentWithoutLookingTheClientUp() throws Exception {
    MockHttpServletRequest request = authorize(AUTHORIZE);
    request.setParameter("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");

    run(request);

    verify(metrics).increment(PkceUsageFilter.METRIC, "pkce", "present", "policy", "unknown");
    verifyNoInteractions(clients);
  }

  @Test
  void aRequestWithoutAChallengeFromAnOptionalClientIsTheRowToWatch() throws Exception {
    clientWith(PkcePolicy.OPTIONAL, true);

    run(authorize(AUTHORIZE));

    verify(metrics).increment(PkceUsageFilter.METRIC, "pkce", "absent", "policy", "OPTIONAL");
  }

  @Test
  void aRequestWithoutAChallengeFromARequiredClientIsCountedAsSuch() throws Exception {
    clientWith(PkcePolicy.REQUIRED, true);

    run(authorize(AUTHORIZE));

    verify(metrics).increment(PkceUsageFilter.METRIC, "pkce", "absent", "policy", "REQUIRED");
  }

  @Test
  void aBlankChallengeIsNotAChallenge() throws Exception {
    clientWith(PkcePolicy.REQUIRED, true);
    MockHttpServletRequest request = authorize(AUTHORIZE);
    request.setParameter("code_challenge", "  ");

    run(request);

    verify(metrics).increment(PkceUsageFilter.METRIC, "pkce", "absent", "policy", "REQUIRED");
  }

  @Test
  void anUnknownOrInactiveClientIsTaggedUnknownNotWithItsPolicy() throws Exception {
    clientWith(PkcePolicy.OPTIONAL, false);
    run(authorize(AUTHORIZE));

    when(clients.findByClientId(CLIENT)).thenReturn(Optional.empty());
    run(authorize(AUTHORIZE));

    verify(metrics, times(2))
        .increment(PkceUsageFilter.METRIC, "pkce", "absent", "policy", "unknown");
  }

  @Test
  void aRequestWithNoClientIdIsTaggedUnknownWithoutALookup() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", AUTHORIZE);
    request.setServletPath(AUTHORIZE);

    run(request);

    verify(metrics).increment(PkceUsageFilter.METRIC, "pkce", "absent", "policy", "unknown");
    verifyNoInteractions(clients);
  }

  @Test
  void anyOtherPathIsLeftAloneAndNothingIsCounted() throws Exception {
    run(authorize("/o/6f1c2d4e-8a3b-4c5d-9e7f-0a1b2c3d4e5f/oauth2/token"));
    run(authorize("/oauth2/authorize"));

    verifyNoInteractions(metrics);
    verifyNoInteractions(clients);
  }

  @Test
  void theRequestAlwaysContinuesDownTheChain() throws Exception {
    clientWith(PkcePolicy.OPTIONAL, true);

    MockFilterChain chain = run(authorize(AUTHORIZE));

    assertThat(chain.getRequest()).isNotNull();
  }
}

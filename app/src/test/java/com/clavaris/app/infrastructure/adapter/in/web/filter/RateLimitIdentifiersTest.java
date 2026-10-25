package com.clavaris.app.infrastructure.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** TD-PERF-034: the token-endpoint rules are keyed by client and source address together. */
class RateLimitIdentifiersTest {

  @Test
  void combinesTheBasicAuthClientIdWithTheSourceAddress() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.7");
    request.addHeader(
        "Authorization",
        "Basic "
            + Base64.getEncoder()
                .encodeToString("client-a:secret".getBytes(StandardCharsets.UTF_8)));

    assertThat(RateLimitIdentifiers.oauthClientIdAndSourceIp(request))
        .isEqualTo("client-a@203.0.113.7");
  }

  @Test
  void fallsBackToTheClientIdFormParameter() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.9");
    request.setParameter("client_id", "public-client");

    assertThat(RateLimitIdentifiers.oauthClientIdAndSourceIp(request))
        .isEqualTo("public-client@198.51.100.9");
  }

  @Test
  void twoAddressesForTheSameClientGetDifferentKeys() {
    final MockHttpServletRequest first = new MockHttpServletRequest();
    first.setParameter("client_id", "c");
    first.setRemoteAddr("203.0.113.7");
    final MockHttpServletRequest second = new MockHttpServletRequest();
    second.setParameter("client_id", "c");
    second.setRemoteAddr("198.51.100.9");

    assertThat(RateLimitIdentifiers.oauthClientIdAndSourceIp(first))
        .isNotEqualTo(RateLimitIdentifiers.oauthClientIdAndSourceIp(second));
  }

  @Test
  void isNullWithoutAClientIdSoTheRuleIsSkipped() {
    assertThat(RateLimitIdentifiers.oauthClientIdAndSourceIp(new MockHttpServletRequest()))
        .isNull();
  }
}
